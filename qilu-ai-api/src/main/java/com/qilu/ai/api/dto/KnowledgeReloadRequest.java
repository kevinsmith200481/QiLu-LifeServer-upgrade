package com.qilu.ai.api.dto;

import java.io.Serializable;
import java.util.List;

public class KnowledgeReloadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String knowledgeVersion;
    private List<KnowledgeSyncItemDTO> documents;

    public String getKnowledgeVersion() {
        return knowledgeVersion;
    }

    public void setKnowledgeVersion(String knowledgeVersion) {
        this.knowledgeVersion = knowledgeVersion;
    }

    public List<KnowledgeSyncItemDTO> getDocuments() {
        return documents;
    }

    public void setDocuments(List<KnowledgeSyncItemDTO> documents) {
        this.documents = documents;
    }
}
