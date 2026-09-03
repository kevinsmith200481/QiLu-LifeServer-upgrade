package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.Result;
import com.qilu.dto.TicketReplyRequest;
import com.qilu.entity.ServiceTicket;

public interface IServiceTicketService extends IService<ServiceTicket> {

    Result createTicket(ServiceTicket ticket);

    Result queryMyTickets(Integer current);

    Result queryTicketDetail(Long id);

    Result queryTicketPage(
            Integer current,
            Integer status,
            Long servicePointId,
            String requester,
            String startTime,
            String endTime,
            String sortOrder,
            Integer studentReplyRequired
    );

    Result hideMyTicket(Long id);

    Result deleteManagedTicket(Long id, String remark);

    Result acceptTicket(Long id);

    Result replyTicket(Long id, TicketReplyRequest request);

    Result assignTicket(Long id, Long assigneeId);

    Result finishTicket(Long id);

    Result closeTicket(Long id);

    Result rejectTicket(Long id);

    Result evaluateTicket(Long id, Integer rating, String evaluation);
}
