package com.qilu.ai.api.dto;

import java.io.Serializable;
import java.util.List;

/** Memory v2 允许持久化的固定实体集合。 */
public class CampusMemoryEntitiesDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<CampusMemoryEntityDTO> tickets;
    private List<CampusMemoryEntityDTO> appointments;
    private List<CampusMemoryEntityDTO> servicePoints;
    private CampusMemoryActionDraftDTO pendingActionDraft;

    public List<CampusMemoryEntityDTO> getTickets() {
        return tickets;
    }

    public void setTickets(List<CampusMemoryEntityDTO> tickets) {
        this.tickets = tickets;
    }

    public List<CampusMemoryEntityDTO> getAppointments() {
        return appointments;
    }

    public void setAppointments(List<CampusMemoryEntityDTO> appointments) {
        this.appointments = appointments;
    }

    public List<CampusMemoryEntityDTO> getServicePoints() {
        return servicePoints;
    }

    public void setServicePoints(List<CampusMemoryEntityDTO> servicePoints) {
        this.servicePoints = servicePoints;
    }

    public CampusMemoryActionDraftDTO getPendingActionDraft() {
        return pendingActionDraft;
    }

    public void setPendingActionDraft(CampusMemoryActionDraftDTO pendingActionDraft) {
        this.pendingActionDraft = pendingActionDraft;
    }
}
