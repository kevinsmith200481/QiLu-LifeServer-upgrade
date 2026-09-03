package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.Result;
import com.qilu.entity.TicketComment;

public interface ITicketCommentService extends IService<TicketComment> {

    Result addComment(TicketComment comment);
}
