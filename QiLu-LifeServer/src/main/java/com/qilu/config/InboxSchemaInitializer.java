package com.qilu.config;

import com.qilu.common.InboxTableRouter;
import com.qilu.mapper.InboxDeadLetterMapper;
import com.qilu.mapper.InboxDeliveryTaskMapper;
import com.qilu.mapper.InboxMessageMapper;
import com.qilu.mapper.InboxUserMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Initializes inbox schemas outside request and consumer transactions.
 *
 * <p>Current and next month are prepared together so a process that stays up
 * across month-end does not execute DDL in its first business request.</p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InboxSchemaInitializer implements ApplicationRunner {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    private final InboxDeliveryTaskMapper taskMapper;
    private final InboxDeadLetterMapper deadLetterMapper;
    private final InboxMessageMapper messageMapper;
    private final InboxUserMessageMapper userMessageMapper;
    private final InboxTableRouter tableRouter;
    private volatile boolean outboxSchemaReady;

    public InboxSchemaInitializer(InboxDeliveryTaskMapper taskMapper,
                                  InboxDeadLetterMapper deadLetterMapper,
                                  InboxMessageMapper messageMapper,
                                  InboxUserMessageMapper userMessageMapper,
                                  InboxTableRouter tableRouter) {
        this.taskMapper = taskMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.messageMapper = messageMapper;
        this.userMessageMapper = userMessageMapper;
        this.tableRouter = tableRouter;
    }

    @Override
    public void run(ApplicationArguments args) {
        initializeRequiredTables();
    }

    @Scheduled(cron = "0 5 0 1 * ?")
    public void initializeRequiredTables() {
        taskMapper.createTable();
        deadLetterMapper.createTable();
        LocalDateTime now = LocalDateTime.now();
        initializeMonth(now.format(MONTH_FORMATTER));
        initializeMonth(now.plusMonths(1).format(MONTH_FORMATTER));
        outboxSchemaReady = taskMapper.countRequiredOutboxColumns() == 3;
        if (outboxSchemaReady) {
            log.info("inbox schema initialized, currentMonth={}, nextMonth={}",
                    now.format(MONTH_FORMATTER), now.plusMonths(1).format(MONTH_FORMATTER));
        } else {
            log.error("legacy inbox_delivery_task schema detected; Outbox relay is disabled until "
                    + "db/inbox_outbox_phase2.sql is applied");
        }
    }

    public boolean isOutboxSchemaReady() {
        return outboxSchemaReady;
    }

    private void initializeMonth(String monthKey) {
        messageMapper.createMessageTable(tableRouter.messageTable(monthKey));
        userMessageMapper.createUserMessageTable(tableRouter.userMessageTable(monthKey));
    }
}
