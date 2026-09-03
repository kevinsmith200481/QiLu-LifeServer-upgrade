package com.qilu.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.Result;
import com.qilu.entity.AiKnowledge;
import com.qilu.mapper.AiKnowledgeMapper;
import com.qilu.service.IAiKnowledgeService;
import com.qilu.utils.SystemConstants;
import org.springframework.stereotype.Service;

@Service
public class AiKnowledgeServiceImpl extends ServiceImpl<AiKnowledgeMapper, AiKnowledge> implements IAiKnowledgeService {

    @Override
    public Result queryAdminPage(Integer current, String category, Integer status, String keyword) {
        Page<AiKnowledge> page = query()
                .eq(StrUtil.isNotBlank(category), "category", category)
                .eq(status != null, "status", status)
                .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                        .like("title", keyword)
                        .or()
                        .like("content", keyword))
                .orderByDesc("update_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    public Result saveKnowledge(AiKnowledge knowledge) {
        Result checkResult = checkKnowledge(knowledge, false);
        if (checkResult != null) {
            return checkResult;
        }
        if (knowledge.getStatus() == null) {
            knowledge.setStatus(1);
        }
        save(knowledge);
        return Result.ok(knowledge.getId());
    }

    @Override
    public Result updateKnowledge(AiKnowledge knowledge) {
        Result checkResult = checkKnowledge(knowledge, true);
        if (checkResult != null) {
            return checkResult;
        }
        boolean success = updateById(knowledge);
        return success ? Result.ok() : Result.fail("Update knowledge failed");
    }

    @Override
    public Result enableKnowledge(Long id) {
        return updateKnowledgeStatus(id, 1);
    }

    @Override
    public Result disableKnowledge(Long id) {
        return updateKnowledgeStatus(id, 0);
    }

    private Result updateKnowledgeStatus(Long id, Integer status) {
        boolean success = update().set("status", status).eq("id", id).update();
        return success ? Result.ok() : Result.fail("Update knowledge status failed");
    }

    private Result checkKnowledge(AiKnowledge knowledge, boolean requireId) {
        if (knowledge == null) {
            return Result.fail("Knowledge is required");
        }
        if (requireId && knowledge.getId() == null) {
            return Result.fail("Knowledge id is required");
        }
        if (StrUtil.isBlank(knowledge.getTitle())) {
            return Result.fail("Knowledge title is required");
        }
        if (StrUtil.isBlank(knowledge.getContent())) {
            return Result.fail("Knowledge content is required");
        }
        return null;
    }
}
