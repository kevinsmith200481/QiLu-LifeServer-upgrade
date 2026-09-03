package com.qilu.ai.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class CampusAssistantSourceDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String type;
    private Long id;
    private Long knowledgeId;
    private String title;
    private String name;
    private String category;
    private String snippet;
    private Double score;
    private String source;
    private String knowledgeVersion;
    private String indexVersion;
    private List<Integer> chunkIndexes;
    private List<String> retrievers;
    private Double fusionScore;
    private Map<String, Double> retrieverScores;
    private Map<String, Double> normalizedRetrieverScores;
    private String address;
    private String openHours;
    private String statusText;
    private Integer readStatus;
    private String createTime;
    private String slotTitle;
    private String startTime;
    private String endTime;
    private String module;
    private String operation;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getKnowledgeId() {
        return knowledgeId;
    }

    public void setKnowledgeId(Long knowledgeId) {
        this.knowledgeId = knowledgeId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getKnowledgeVersion() {
        return knowledgeVersion;
    }

    public void setKnowledgeVersion(String knowledgeVersion) {
        this.knowledgeVersion = knowledgeVersion;
    }

    public String getIndexVersion() {
        return indexVersion;
    }

    public void setIndexVersion(String indexVersion) {
        this.indexVersion = indexVersion;
    }

    public List<Integer> getChunkIndexes() {
        return chunkIndexes;
    }

    public void setChunkIndexes(List<Integer> chunkIndexes) {
        this.chunkIndexes = chunkIndexes;
    }

    public List<String> getRetrievers() {
        return retrievers;
    }

    public void setRetrievers(List<String> retrievers) {
        this.retrievers = retrievers;
    }

    public Double getFusionScore() {
        return fusionScore;
    }

    public void setFusionScore(Double fusionScore) {
        this.fusionScore = fusionScore;
    }

    public Map<String, Double> getRetrieverScores() {
        return retrieverScores;
    }

    public void setRetrieverScores(Map<String, Double> retrieverScores) {
        this.retrieverScores = retrieverScores;
    }

    public Map<String, Double> getNormalizedRetrieverScores() {
        return normalizedRetrieverScores;
    }

    public void setNormalizedRetrieverScores(Map<String, Double> normalizedRetrieverScores) {
        this.normalizedRetrieverScores = normalizedRetrieverScores;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getOpenHours() {
        return openHours;
    }

    public void setOpenHours(String openHours) {
        this.openHours = openHours;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }

    public Integer getReadStatus() {
        return readStatus;
    }

    public void setReadStatus(Integer readStatus) {
        this.readStatus = readStatus;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getSlotTitle() {
        return slotTitle;
    }

    public void setSlotTitle(String slotTitle) {
        this.slotTitle = slotTitle;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }
}
