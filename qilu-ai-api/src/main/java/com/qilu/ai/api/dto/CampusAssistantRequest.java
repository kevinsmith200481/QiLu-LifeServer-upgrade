package com.qilu.ai.api.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class CampusAssistantRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String role;
    private String traceId;
    private String traceParent;
    private String conversationId;
    private String turnId;
    private String question;
    private List<CampusServicePointDTO> servicePoints;
    private List<CampusTicketDTO> tickets;
    private List<CampusAppointmentDTO> appointments;
    private List<Map<String, Object>> history;
    private Map<String, Object> lastBusinessContext;
    private CampusMemoryDTO memory;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getTraceParent() {
        return traceParent;
    }

    public void setTraceParent(String traceParent) {
        this.traceParent = traceParent;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

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

    public List<CampusServicePointDTO> getServicePoints() {
        return servicePoints;
    }

    public void setServicePoints(List<CampusServicePointDTO> servicePoints) {
        this.servicePoints = servicePoints;
    }

    public List<CampusTicketDTO> getTickets() {
        return tickets;
    }

    public void setTickets(List<CampusTicketDTO> tickets) {
        this.tickets = tickets;
    }

    public List<CampusAppointmentDTO> getAppointments() {
        return appointments;
    }

    public void setAppointments(List<CampusAppointmentDTO> appointments) {
        this.appointments = appointments;
    }

    public List<Map<String, Object>> getHistory() {
        return history;
    }

    public void setHistory(List<Map<String, Object>> history) {
        this.history = history;
    }

    public Map<String, Object> getLastBusinessContext() {
        return lastBusinessContext;
    }

    public void setLastBusinessContext(Map<String, Object> lastBusinessContext) {
        this.lastBusinessContext = lastBusinessContext;
    }

    public CampusMemoryDTO getMemory() {
        return memory;
    }

    public void setMemory(CampusMemoryDTO memory) {
        this.memory = memory;
    }
}
