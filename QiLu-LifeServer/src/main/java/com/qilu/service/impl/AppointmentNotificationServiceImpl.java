package com.qilu.service.impl;

import com.qilu.config.AppointmentMqConfig;
import com.qilu.dto.AppointmentEvent;
import com.qilu.dto.InboxSendRequest;
import com.qilu.dto.Result;
import com.qilu.enums.InboxMessageType;
import com.qilu.enums.InboxTargetType;
import com.qilu.service.IAppointmentNotificationService;
import com.qilu.service.IInboxMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AppointmentNotificationServiceImpl implements IAppointmentNotificationService {

    public static final String RESERVED = "APPOINTMENT_RESERVED";
    public static final String CANCELED = "APPOINTMENT_CANCELED";
    public static final String FINISHED = "APPOINTMENT_FINISHED";
    public static final String EXPIRED = "APPOINTMENT_EXPIRED";
    public static final String NO_SHOW = "APPOINTMENT_NO_SHOW";
    public static final String SLOT_CLOSED = "APPOINTMENT_SLOT_CLOSED";
    public static final String REMINDER = "APPOINTMENT_REMINDER";

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private IInboxMessageService inboxMessageService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void publish(AppointmentEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    AppointmentMqConfig.APPOINTMENT_EXCHANGE,
                    AppointmentMqConfig.APPOINTMENT_EVENT_ROUTING_KEY,
                    event
            );
        } catch (AmqpException e) {
            log.warn("Appointment MQ unavailable, fallback to local notification, event={}", event, e);
            consume(event);
        }
    }

    @Override
    public void consume(AppointmentEvent event) {
        if (event == null || event.getEventType() == null) {
            return;
        }
        switch (event.getEventType()) {
            case RESERVED:
                sendUserMessage(event, "预约成功", buildUserReservedContent(event));
                sendManagerMessage(event, "新的预约待处理", buildManagerReservedContent(event));
                break;
            case CANCELED:
                sendUserMessage(event, "预约已取消", buildUserCanceledContent(event));
                sendManagerMessage(event, "预约已取消", buildManagerCanceledContent(event));
                break;
            case FINISHED:
                sendUserMessage(event, "预约已完成", buildFinishedContent(event));
                break;
            case EXPIRED:
                sendUserMessage(event, "预约已过期", buildExpiredContent(event));
                break;
            case NO_SHOW:
                sendUserMessage(event, "预约已标记爽约", buildNoShowContent(event));
                break;
            case SLOT_CLOSED:
                sendUserMessage(event, "预约时段已关闭", buildSlotClosedContent(event));
                break;
            case REMINDER:
                sendUserMessage(event, "预约即将开始", buildReminderContent(event));
                break;
            default:
                log.warn("Unsupported appointment event type: {}", event.getEventType());
        }
    }

    private void sendUserMessage(AppointmentEvent event, String title, String content) {
        if (event.getUserId() == null) {
            return;
        }
        sendMessage(event, event.getUserId(), title, content);
    }

    private void sendManagerMessage(AppointmentEvent event, String title, String content) {
        if (event.getManagerId() == null) {
            return;
        }
        sendMessage(event, event.getManagerId(), title, content);
    }

    private void sendMessage(AppointmentEvent event, Long userId, String title, String content) {
        String dedupKey = "appointment:notify:" + event.getEventId() + ":" + userId;
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(dedupKey, "PROCESSING", 30, TimeUnit.DAYS);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        InboxSendRequest request = new InboxSendRequest();
        request.setMessageType(InboxMessageType.BUSINESS_REMINDER.getCode());
        request.setTargetType(InboxTargetType.USER.getCode());
        request.setTitle(title);
        request.setContent(content);
        request.setSummary(content);
        request.setBusinessType("APPOINTMENT");
        request.setBusinessId(event.getOrderId());
        request.setUserIds(Collections.singletonList(userId));
        try {
            Result result = inboxMessageService.sendInternal(request, null);
            if (!Boolean.TRUE.equals(result.getSuccess())) {
                throw new IllegalStateException(result.getErrorMsg());
            }
            stringRedisTemplate.opsForValue().set(dedupKey, "DONE", 30, TimeUnit.DAYS);
        } catch (RuntimeException e) {
            stringRedisTemplate.delete(dedupKey);
            throw e;
        }
    }

    private String buildUserReservedContent(AppointmentEvent event) {
        return "你已成功预约：" + displaySlot(event) + "。";
    }

    private String buildManagerReservedContent(AppointmentEvent event) {
        return "有新的预约需要处理：" + displaySlot(event) + "。";
    }

    private String buildUserCanceledContent(AppointmentEvent event) {
        return "你的预约已取消：" + displaySlot(event) + "。";
    }

    private String buildManagerCanceledContent(AppointmentEvent event) {
        return "学生已取消预约：" + displaySlot(event) + "。";
    }

    private String buildFinishedContent(AppointmentEvent event) {
        return "你的预约已完成：" + displaySlot(event) + remarkSuffix(event);
    }

    private String buildExpiredContent(AppointmentEvent event) {
        return "你的预约已过期：" + displaySlot(event) + "。";
    }

    private String buildNoShowContent(AppointmentEvent event) {
        return "你的预约已被标记为爽约：" + displaySlot(event) + remarkSuffix(event);
    }

    private String buildSlotClosedContent(AppointmentEvent event) {
        return "预约时段已关闭：" + displaySlot(event) + "。如需帮助，请联系服务点。";
    }

    private String buildReminderContent(AppointmentEvent event) {
        return "你的预约即将开始，请按时前往：" + displaySlot(event) + "。";
    }

    private String displaySlot(AppointmentEvent event) {
        String title = event.getSlotTitle() == null ? "预约时段" : event.getSlotTitle();
        String point = event.getServicePointName() == null ? "服务点" : event.getServicePointName();
        return title + "，" + point + "，" + event.getStartTime() + " 至 " + event.getEndTime();
    }

    private String remarkSuffix(AppointmentEvent event) {
        if (event.getRemark() == null || event.getRemark().trim().isEmpty()) {
            return "。";
        }
        return "。处理备注：" + event.getRemark().trim();
    }
}
