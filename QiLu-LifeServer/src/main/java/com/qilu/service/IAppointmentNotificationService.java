package com.qilu.service;

import com.qilu.dto.AppointmentEvent;

public interface IAppointmentNotificationService {

    void publish(AppointmentEvent event);

    void consume(AppointmentEvent event);
}
