package com.qilu.dto.ai;

import java.util.Map;

public class CampusAssistantChatRequest {

    private String question;
    private Long categoryId;
    private Long sessionId;
    private Map<String, Object> lastBusinessContext;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Map<String, Object> getLastBusinessContext() {
        return lastBusinessContext;
    }

    public void setLastBusinessContext(Map<String, Object> lastBusinessContext) {
        this.lastBusinessContext = lastBusinessContext;
    }
}
