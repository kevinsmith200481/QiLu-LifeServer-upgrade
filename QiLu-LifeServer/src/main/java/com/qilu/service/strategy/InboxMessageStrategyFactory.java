package com.qilu.service.strategy;

import com.qilu.enums.InboxMessageType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class InboxMessageStrategyFactory {

    private final Map<InboxMessageType, InboxMessageStrategy> strategyMap = new EnumMap<>(InboxMessageType.class);

    public InboxMessageStrategyFactory(List<InboxMessageStrategy> strategies) {
        for (InboxMessageStrategy strategy : strategies) {
            strategyMap.put(strategy.supportType(), strategy);
        }
    }

    public InboxMessageStrategy getStrategy(String messageType) {
        InboxMessageType type = InboxMessageType.of(messageType);
        InboxMessageStrategy strategy = strategyMap.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("message strategy not found: " + messageType);
        }
        return strategy;
    }
}
