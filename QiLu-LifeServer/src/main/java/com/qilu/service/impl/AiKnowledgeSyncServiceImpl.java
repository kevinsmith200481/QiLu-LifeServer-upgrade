package com.qilu.service.impl;

import cn.hutool.core.util.StrUtil;
import com.qilu.ai.api.dto.KnowledgeReloadRequest;
import com.qilu.ai.api.dto.KnowledgeReloadResponse;
import com.qilu.ai.api.dto.KnowledgeSyncItemDTO;
import com.qilu.ai.api.service.AiKnowledgeService;
import com.qilu.dto.Result;
import com.qilu.entity.AiKnowledge;
import com.qilu.service.IAiKnowledgeService;
import com.qilu.service.IAiKnowledgeSyncService;
import gamer.springboot.starter.annotation.RpcReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AiKnowledgeSyncServiceImpl implements IAiKnowledgeSyncService {

    @Resource
    private IAiKnowledgeService aiKnowledgeService;

    @RpcReference(interfaceClass = AiKnowledgeService.class)
    private AiKnowledgeService aiKnowledgeServiceRpc;

    @Override
    public Result syncEnabledKnowledgeToAgent() {
        List<AiKnowledge> knowledgeList = aiKnowledgeService.query()
                .eq("status", 1)
                .orderByAsc("category")
                .orderByDesc("update_time")
                .list();
        KnowledgeReloadRequest request = new KnowledgeReloadRequest();
        request.setDocuments(knowledgeList.stream().map(this::toSyncItem).collect(Collectors.toList()));
        request.setKnowledgeVersion(buildKnowledgeVersion(request.getDocuments()));
        try {
            log.info("Start syncing AI knowledge to agent, enabledKnowledgeCount={}, knowledgeVersion={}",
                    request.getDocuments().size(), request.getKnowledgeVersion());
            KnowledgeReloadResponse response = aiKnowledgeServiceRpc.reloadKnowledge(request);
            if (response != null && Boolean.TRUE.equals(response.getSuccess())) {
                log.info("AI knowledge sync succeeded, sourceDocumentCount={}, chunkCount={}, knowledgeVersion={}, "
                                + "indexVersion={}, degraded={}, syncedInstances={}/{}, message={}",
                        response.getSourceDocumentCount(), response.getChunkCount(), response.getKnowledgeVersion(),
                        response.getActiveIndexVersion(), response.getDegraded(),
                        response.getSyncedInstanceCount(), response.getInstanceCount(), response.getMessage());
                return Result.ok(response);
            }
            String message = response == null ? "AI knowledge sync returned empty response" : response.getMessage();
            String errorCode = response == null ? "RAG_EMPTY_PROVIDER_RESPONSE" : response.getErrorCode();
            log.warn("AI knowledge sync failed, enabledKnowledgeCount={}, errorCode={}, message={}",
                    request.getDocuments().size(), errorCode, message);
            return Result.fail(StrUtil.blankToDefault(message, "AI knowledge sync failed"));
        } catch (RuntimeException e) {
            log.warn("AI knowledge sync failed, enabledKnowledgeCount={}", request.getDocuments().size(), e);
            return Result.fail("AI knowledge sync failed: " + e.getMessage());
        }
    }

    private KnowledgeSyncItemDTO toSyncItem(AiKnowledge knowledge) {
        KnowledgeSyncItemDTO item = new KnowledgeSyncItemDTO();
        item.setId(knowledge.getId());
        item.setTitle(knowledge.getTitle());
        item.setContent(knowledge.getContent());
        item.setCategory(knowledge.getCategory());
        item.setSource(knowledge.getSource());
        item.setKeywords(buildKeywords(knowledge));
        return item;
    }

    private List<String> buildKeywords(AiKnowledge knowledge) {
        String text = String.join(" ",
                safe(knowledge.getTitle()),
                safe(knowledge.getCategory()),
                safe(knowledge.getSource()));
        List<String> keywords = Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9\\u4e00-\\u9fa5]+"))
                .filter(StrUtil::isNotBlank)
                .distinct()
                .limit(12)
                .collect(Collectors.toList());
        return keywords.isEmpty() ? Collections.emptyList() : keywords;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String buildKnowledgeVersion(List<KnowledgeSyncItemDTO> documents) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (documents != null) {
                for (KnowledgeSyncItemDTO item : documents) {
                    updateDigest(digest, item == null ? null : String.valueOf(item.getId()));
                    updateDigest(digest, item == null ? null : item.getTitle());
                    updateDigest(digest, item == null ? null : item.getContent());
                    updateDigest(digest, item == null ? null : item.getCategory());
                    updateDigest(digest, item == null ? null : item.getSource());
                    if (item != null && item.getKeywords() != null) {
                        for (String keyword : item.getKeywords()) {
                            updateDigest(digest, keyword);
                        }
                    }
                }
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder("kb-");
            for (int i = 0; i < 8 && i < hash.length; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return "kb-" + System.currentTimeMillis();
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(safe(value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
