package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.Result;
import com.qilu.entity.AiKnowledge;

public interface IAiKnowledgeService extends IService<AiKnowledge> {

    Result queryAdminPage(Integer current, String category, Integer status, String keyword);

    Result saveKnowledge(AiKnowledge knowledge);

    Result updateKnowledge(AiKnowledge knowledge);

    Result enableKnowledge(Long id);

    Result disableKnowledge(Long id);
}
