package com.qilu.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class StationCommentDTO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long stationId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long rootId;

    private Integer floorNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String userName;

    private String userIcon;

    private String userType;

    private String content;

    private Integer likeCount;

    private Boolean liked;

    private Integer replyCount;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long replyToCommentId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long replyToUserId;

    private String replyToUserName;

    private String replyToContent;

    private LocalDateTime createTime;
}
