package com.qilu.ai.api.dto;

import java.io.Serializable;

public class TicketCategoryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String category;
    private Double confidence;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }
}
