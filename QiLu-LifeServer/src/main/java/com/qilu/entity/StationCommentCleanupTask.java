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
@TableName("station_comment_cleanup_task")
public class StationCommentCleanupTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String messageId;

    private Long stationId;

    private Long rootCommentId;

    private Long deletedBy;

    private Integer status;

    private Integer retryCount;

    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
