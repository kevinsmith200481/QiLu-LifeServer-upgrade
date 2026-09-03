package com.qilu.mq;

import com.qilu.config.StationCommentMqConfig;
import com.qilu.dto.StationCommentDeleteMessage;
import com.qilu.service.IStationCommentService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class StationCommentDeleteListener {

    @Resource
    private IStationCommentService stationCommentService;

    @RabbitListener(queues = StationCommentMqConfig.COMMENT_DELETE_QUEUE)
    public void onDeleteMessage(StationCommentDeleteMessage message) {
        stationCommentService.cleanupDeletedFloor(message);
    }
}
