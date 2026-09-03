package com.qilu.service;

import com.qilu.vo.InboxRealtimePayload;

public interface IInboxRealtimeService {

    void publish(InboxRealtimePayload payload);

    void pushLocal(InboxRealtimePayload payload);
}
