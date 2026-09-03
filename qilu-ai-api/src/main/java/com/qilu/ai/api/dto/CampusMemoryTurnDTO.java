package com.qilu.ai.api.dto;

import java.io.Serializable;

/** 有界的完整 user/assistant 轮次，不包含任意扩展 metadata。 */
public class CampusMemoryTurnDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String turnId;
    private String question;
    private String answer;
    private String intent;

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }
}
