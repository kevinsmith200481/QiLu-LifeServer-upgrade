package com.qilu.service;

import com.qilu.dto.InboxDeliveryEvent;

public interface IInboxDeliveryService {

    void consumeDelivery(InboxDeliveryEvent event);

    void recordDeliveryFailure(InboxDeliveryEvent event, Throwable failure);
}
