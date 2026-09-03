package com.qilu.service.strategy;

import com.qilu.enums.InboxMessageType;
import org.springframework.stereotype.Component;

@Component
public class SiteReplyStrategy extends AbstractInboxMessageStrategy {

    @Override
    public InboxMessageType supportType() {
        return InboxMessageType.SITE_REPLY;
    }
}
