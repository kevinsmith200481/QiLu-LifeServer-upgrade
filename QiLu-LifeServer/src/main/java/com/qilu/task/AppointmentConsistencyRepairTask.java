package com.qilu.task;

import com.qilu.service.IAppointmentConsistencyRepairService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class AppointmentConsistencyRepairTask {

    @Resource
    private IAppointmentConsistencyRepairService repairService;

    @Scheduled(fixedDelayString = "${qilu.appointment.repair.fixed-delay-ms:5000}")
    public void repairPendingAppointmentConsistencyTasks() {
        int repaired = repairService.repairPendingTasks();
        if (repaired > 0) {
            log.info("Repaired appointment consistency tasks, count={}", repaired);
        }
    }
}
