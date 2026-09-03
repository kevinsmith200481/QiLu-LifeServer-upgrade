package com.qilu.mq;

import com.qilu.config.AppointmentMqConfig;
import com.qilu.dto.AppointmentEvent;
import com.qilu.service.IAppointmentFailureLogService;
import com.qilu.service.IAppointmentNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class AppointmentEventListener {

    private static final int MAX_RETRY_COUNT = 3;

    @Resource
    private IAppointmentNotificationService appointmentNotificationService;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private IAppointmentFailureLogService appointmentFailureLogService;

    @RabbitListener(queues = AppointmentMqConfig.APPOINTMENT_NOTIFICATION_QUEUE)
    public void onAppointmentEvent(AppointmentEvent event) {
        try {
            appointmentNotificationService.consume(event);
        } catch (Exception e) {
            retryOrDead(event, e);
        }
    }

    @RabbitListener(queues = AppointmentMqConfig.APPOINTMENT_DEAD_QUEUE)
    public void onDeadAppointmentEvent(AppointmentEvent event) {
        log.error("Appointment event moved to dead queue, event={}", event);
        appointmentFailureLogService.logNotificationDead(event, "rabbit dead letter");
    }

    private void retryOrDead(AppointmentEvent event, Exception e) {
        int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
        event.setRetryCount(retryCount + 1);
        event.setErrorMsg(e.getMessage());
        if (retryCount + 1 >= MAX_RETRY_COUNT) {
            log.error("Appointment event consume failed after retries, event={}", event, e);
            appointmentFailureLogService.logNotificationDead(event, e.getMessage());
            rabbitTemplate.convertAndSend(
                    AppointmentMqConfig.APPOINTMENT_DEAD_EXCHANGE,
                    AppointmentMqConfig.APPOINTMENT_DEAD_ROUTING_KEY,
                    event
            );
            return;
        }
        log.warn("Appointment event consume failed, retry later, event={}", event, e);
        rabbitTemplate.convertAndSend(
                AppointmentMqConfig.APPOINTMENT_RETRY_EXCHANGE,
                AppointmentMqConfig.APPOINTMENT_RETRY_ROUTING_KEY,
                event
        );
    }
}
