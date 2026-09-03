package com.qilu.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class StationCommentCreateRequest {

    private Long parentId;

    private Long replyToCommentId;

    @NotBlank(message = "content is required")
    @Size(max = 1024, message = "content length must be less than 1024")
    private String content;
}
