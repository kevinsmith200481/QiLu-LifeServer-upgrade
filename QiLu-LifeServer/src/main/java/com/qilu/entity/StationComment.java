package com.qilu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("station_comment")
public class StationComment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long stationId;

    private Long parentId;

    private Long rootId;

    private Long replyToCommentId;

    private Long replyToUserId;

    private Long userId;

    private String userType;

    private Integer floorNo;

    private String content;

    private Integer likeCount;

    private Integer replyCount;

    private Integer status;

    private Long deletedBy;

    private LocalDateTime deleteTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
