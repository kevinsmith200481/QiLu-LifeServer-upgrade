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
@TableName("admin_comment_view")
public class AdminCommentView implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "comment_id", type = IdType.INPUT)
    private Long commentId;

    private Long stationId;

    private Long parentId;

    private Long rootId;

    private Integer floorNo;

    private Long adminId;

    private String adminType;

    private String content;

    private Long replyToCommentId;

    private Long replyToUserId;

    private String replyToUserName;

    private String replyToContent;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
