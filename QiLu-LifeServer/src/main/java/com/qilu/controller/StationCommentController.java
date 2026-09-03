package com.qilu.controller;

import com.qilu.annotation.Log;
import com.qilu.dto.Result;
import com.qilu.dto.StationCommentCreateRequest;
import com.qilu.service.IStationCommentService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.Valid;

@Validated
@RestController
@RequestMapping("/service-point/{stationId}/comments")
public class StationCommentController {

    @Resource
    private IStationCommentService stationCommentService;

    @PostMapping
    @Log(module = "StationComment", operation = "Create station comment")
    public Result createComment(@PathVariable("stationId") Long stationId,
                                @Valid @RequestBody StationCommentCreateRequest request) {
        return stationCommentService.createComment(stationId, request);
    }

    @GetMapping
    public Result queryComments(@PathVariable("stationId") Long stationId,
                                @RequestParam(value = "sort", defaultValue = "latest") String sort,
                                @RequestParam(value = "cursor", required = false) Long cursor,
                                @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return stationCommentService.queryByCursor(stationId, sort, cursor, size);
    }

    @GetMapping("/hot")
    public Result queryHotComments(@PathVariable("stationId") Long stationId,
                                   @RequestParam(value = "cursorScore", required = false) Double cursorScore,
                                   @RequestParam(value = "offset", defaultValue = "0") Integer offset,
                                   @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return stationCommentService.queryHot(stationId, cursorScore, offset, size);
    }

    @GetMapping("/admin")
    public Result queryAdminComments(@PathVariable("stationId") Long stationId,
                                     @RequestParam(value = "cursor", required = false) Long cursor,
                                     @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return stationCommentService.queryAdminComments(stationId, cursor, size);
    }

    @PutMapping("/{commentId}/like")
    public Result likeComment(@PathVariable("stationId") Long stationId,
                              @PathVariable("commentId") Long commentId) {
        return stationCommentService.likeComment(stationId, commentId);
    }

    @GetMapping("/{rootCommentId}/replies")
    public Result queryReplies(@PathVariable("stationId") Long stationId,
                               @PathVariable("rootCommentId") Long rootCommentId,
                               @RequestParam(value = "cursor", required = false) Long cursor,
                               @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return stationCommentService.queryReplies(stationId, rootCommentId, cursor, size);
    }

    @DeleteMapping("/{commentId}")
    @Log(module = "StationComment", operation = "Delete station comment")
    public Result deleteComment(@PathVariable("stationId") Long stationId,
                                @PathVariable("commentId") Long commentId) {
        return stationCommentService.deleteComment(stationId, commentId);
    }
}
