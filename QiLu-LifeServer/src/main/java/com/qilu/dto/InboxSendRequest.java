package com.qilu.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class InboxSendRequest {

    @NotBlank(message = "messageType is required")
    private String messageType;

    @NotBlank(message = "targetType is required")
    private String targetType;

    @NotBlank(message = "title is required")
    @Size(max = 128, message = "title is too long")
    private String title;

    @NotBlank(message = "content is required")
    @Size(max = 4096, message = "content is too long")
    private String content;

    @Size(max = 512, message = "summary is too long")
    private String summary;

    @Size(max = 64, message = "businessType is too long")
    private String businessType;

    private Long businessId;

    private List<@NotNull(message = "target user id is required") Long> userIds;

    private List<@NotBlank(message = "target role is required") String> roles;

    private LocalDateTime expireTime;
}
