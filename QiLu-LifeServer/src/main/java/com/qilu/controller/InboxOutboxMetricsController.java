package com.qilu.controller;

import com.qilu.service.InboxOutboxMetrics;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/actuator")
public class InboxOutboxMetricsController {

    private final InboxOutboxMetrics metrics;

    public InboxOutboxMetricsController(InboxOutboxMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping(value = "/prometheus", produces = MediaType.TEXT_PLAIN_VALUE)
    public String prometheus() {
        return metrics.prometheusSnapshot();
    }
}
