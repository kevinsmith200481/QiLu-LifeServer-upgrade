package com.qilu.controller;

import com.qilu.annotation.Log;
import com.qilu.dto.Result;
import com.qilu.entity.AiKnowledge;
import com.qilu.service.IAiKnowledgeService;
import com.qilu.service.IAiKnowledgeSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/admin/ai-knowledge")
@Slf4j
public class AdminAiKnowledgeController {

    @Resource
    private IAiKnowledgeService aiKnowledgeService;

    @Resource
    private IAiKnowledgeSyncService aiKnowledgeSyncService;

    @GetMapping("/page")
    public Result queryKnowledgePage(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return aiKnowledgeService.queryAdminPage(current, category, status, keyword);
    }

    @PostMapping
    @Log(module = "AiKnowledge", operation = "Create AI knowledge")
    public Result saveKnowledge(@RequestBody AiKnowledge knowledge) {
        Result result = aiKnowledgeService.saveKnowledge(knowledge);
        syncAfterSuccess(result);
        return result;
    }

    @PutMapping
    @Log(module = "AiKnowledge", operation = "Update AI knowledge")
    public Result updateKnowledge(@RequestBody AiKnowledge knowledge) {
        Result result = aiKnowledgeService.updateKnowledge(knowledge);
        syncAfterSuccess(result);
        return result;
    }

    @PutMapping("/{id}/enable")
    @Log(module = "AiKnowledge", operation = "Enable AI knowledge")
    public Result enableKnowledge(@PathVariable("id") Long id) {
        Result result = aiKnowledgeService.enableKnowledge(id);
        syncAfterSuccess(result);
        return result;
    }

    @PutMapping("/{id}/disable")
    @Log(module = "AiKnowledge", operation = "Disable AI knowledge")
    public Result disableKnowledge(@PathVariable("id") Long id) {
        Result result = aiKnowledgeService.disableKnowledge(id);
        syncAfterSuccess(result);
        return result;
    }

    @PutMapping("/sync-agent")
    @Log(module = "AiKnowledge", operation = "Sync AI knowledge to agent")
    public Result syncKnowledgeToAgent() {
        return aiKnowledgeSyncService.syncEnabledKnowledgeToAgent();
    }

    private void syncAfterSuccess(Result result) {
        if (result == null || !Boolean.TRUE.equals(result.getSuccess())) {
            return;
        }
        try {
            Result syncResult = aiKnowledgeSyncService.syncEnabledKnowledgeToAgent();
            if (syncResult == null || !Boolean.TRUE.equals(syncResult.getSuccess())) {
                log.warn("AI knowledge changed but automatic sync failed, reason={}",
                        syncResult == null ? "empty sync result" : syncResult.getErrorMsg());
            }
        } catch (RuntimeException e) {
            log.warn("AI knowledge changed but automatic sync threw exception", e);
        }
    }
}
