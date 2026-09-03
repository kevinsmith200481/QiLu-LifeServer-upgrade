package com.qilu.task;

import com.qilu.service.IStationCommentService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class StationCommentSyncTask {

    @Resource
    private IStationCommentService stationCommentService;

    @Scheduled(fixedDelay = 300000)
    public void rebuildHotComments() {
        stationCommentService.rebuildHotComments();
    }

    @Scheduled(fixedDelay = 300000)
    public void rebuildAdminCommentView() {
        stationCommentService.rebuildAdminCommentView();
    }

    @Scheduled(fixedDelay = 60000)
    public void retryCleanupTasks() {
        stationCommentService.retryCleanupTasks();
    }
}
