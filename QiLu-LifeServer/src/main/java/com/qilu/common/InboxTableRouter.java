package com.qilu.common;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class InboxTableRouter {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    public String currentMonthKey() {
        return LocalDateTime.now().format(MONTH_FORMATTER);
    }

    public String normalizeMonthKey(String monthKey) {
        if (monthKey == null || monthKey.length() == 0) {
            return currentMonthKey();
        }
        if (!monthKey.matches("\\d{6}")) {
            throw new IllegalArgumentException("monthKey must be yyyyMM");
        }
        return monthKey;
    }

    public String messageTable(String monthKey) {
        return "inbox_message_" + normalizeMonthKey(monthKey);
    }

    public String userMessageTable(String monthKey) {
        return "inbox_user_message_" + normalizeMonthKey(monthKey);
    }
}
