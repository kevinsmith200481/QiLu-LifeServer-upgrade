package com.qilu.task;

import com.qilu.common.InboxTableRouter;
import com.qilu.mapper.InboxMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class InboxMessageLifecycleTask {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    @Resource
    private InboxTableRouter inboxTableRouter;

    @Resource
    private InboxMessageMapper inboxMessageMapper;

    @Scheduled(cron = "0 */10 * * * ?")
    public void expireMessages() {
        String monthKey = inboxTableRouter.currentMonthKey();
        int updated = inboxMessageMapper.expireMessages(inboxTableRouter.messageTable(monthKey));
        if (updated > 0) {
            log.info("expired inbox messages, monthKey={}, count={}", monthKey, updated);
        }
    }

    @Scheduled(cron = "0 10 3 1 * ?")
    public void archiveColdDataHint() {
        /*
         * 设计说明：消息主表和用户副本表按月水平分表，线上冷数据归档通常交给低峰定时任务或 DBA 运维脚本。
         * 当前任务保留归档入口和日志提示，配套 SQL 文件中提供 archive 表结构与 INSERT ... SELECT / DROP PARTITION 式迁移模板。
         */
        String coldMonth = LocalDateTime.now().minusMonths(6).format(MONTH_FORMATTER);
        log.info("inbox cold data archive window reached, coldMonth={}, please execute verified archive sql", coldMonth);
    }
}
