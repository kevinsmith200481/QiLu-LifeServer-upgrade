package com.qilu.service.strategy;

import cn.hutool.core.collection.CollUtil;
import com.qilu.dto.InboxSendRequest;
import com.qilu.enums.InboxMessageType;
import com.qilu.enums.InboxTargetType;
import org.springframework.stereotype.Component;

@Component
public class ExceptionAlertStrategy extends AbstractInboxMessageStrategy {

    @Override
    public InboxMessageType supportType() {
        return InboxMessageType.EXCEPTION_ALERT;
    }

    @Override
    public void validate(InboxSendRequest request) {
        super.validate(request);
        InboxTargetType targetType = InboxTargetType.of(request.getTargetType());
        if (targetType == InboxTargetType.ALL) {
            throw new IllegalArgumentException("exception alert can not push to all users");
        }
        if (targetType == InboxTargetType.ROLE
                && (CollUtil.isEmpty(request.getRoles()) || !request.getRoles().contains("admin"))) {
            throw new IllegalArgumentException("exception alert is visible to admin only");
        }
    }
}
