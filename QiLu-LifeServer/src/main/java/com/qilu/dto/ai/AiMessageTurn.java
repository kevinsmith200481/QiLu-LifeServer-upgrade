package com.qilu.dto.ai;

import com.qilu.entity.AiMessage;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 已完成的会话轮次。
 *
 * 只有能够稳定配对的 user 和 assistant 消息才会构造该对象，避免把半轮消息传给智能体。
 */
@Getter
@AllArgsConstructor
public class AiMessageTurn {

    private final AiMessage userMessage;
    private final AiMessage assistantMessage;

    public Long getCompletionMessageId() {
        return assistantMessage.getId();
    }
}
