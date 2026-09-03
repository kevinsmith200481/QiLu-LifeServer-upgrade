package com.qilu.service.strategy;

import com.qilu.enums.InboxMessageType;
import org.springframework.stereotype.Component;

@Component
public class ApprovalNoticeStrategy extends AbstractInboxMessageStrategy {

    @Override
    public InboxMessageType supportType() {
        return InboxMessageType.APPROVAL_NOTICE;
    }
}
