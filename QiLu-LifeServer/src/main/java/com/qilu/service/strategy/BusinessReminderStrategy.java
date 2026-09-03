package com.qilu.service.strategy;

import com.qilu.enums.InboxMessageType;
import org.springframework.stereotype.Component;

@Component
public class BusinessReminderStrategy extends AbstractInboxMessageStrategy {

    @Override
    public InboxMessageType supportType() {
        return InboxMessageType.BUSINESS_REMINDER;
    }
}
