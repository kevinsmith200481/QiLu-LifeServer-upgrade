package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.Result;
import com.qilu.dto.StationCommentCreateRequest;
import com.qilu.dto.StationCommentDeleteMessage;
import com.qilu.entity.StationComment;

public interface IStationCommentService extends IService<StationComment> {

    Result createComment(Long stationId, StationCommentCreateRequest request);

    Result queryByCursor(Long stationId, String sort, Long cursor, Integer size);

    Result queryReplies(Long stationId, Long rootCommentId, Long cursor, Integer size);

    Result queryHot(Long stationId, Double cursorScore, Integer offset, Integer size);

    Result queryAdminComments(Long stationId, Long cursor, Integer size);

    Result likeComment(Long stationId, Long commentId);

    Result deleteComment(Long stationId, Long commentId);

    void cleanupDeletedFloor(StationCommentDeleteMessage message);

    void rebuildHotComments();

    void rebuildAdminCommentView();

    void retryCleanupTasks();
}
