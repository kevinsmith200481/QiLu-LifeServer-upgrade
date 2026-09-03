package com.qilu.task;

import com.qilu.service.IAppointmentOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class AppointmentOrderLifecycleTask {

    @Resource
    private IAppointmentOrderService appointmentOrderService;

    @Scheduled(cron = "0 */5 * * * ?")
    public void expireReservedOrders() {
        int count = appointmentOrderService.expireReservedOrders();
        if (count > 0) {
            log.info("Expired appointment orders, count={}", count);
        }
    }

    @Scheduled(cron = "0 */5 * * * ?")
    public void remindUpcomingOrders() {
        int count = appointmentOrderService.sendUpcomingReminders();
        if (count > 0) {
            log.info("Sent appointment reminders, count={}", count);
        }
    }
}
