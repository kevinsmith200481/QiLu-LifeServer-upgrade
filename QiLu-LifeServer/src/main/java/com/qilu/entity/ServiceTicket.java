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
@TableName("service_ticket")
public class ServiceTicket implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long servicePointId;
    private Long categoryId;
    private String contactPhone;
    private String detailAddress;
    private String attachmentName;
    private String attachmentUrl;
    private Long attachmentSize;
    private String attachmentType;
    private Integer userHidden;
    private Integer adminDeleted;
    private String deleteRemark;
    private Long deletedBy;
    private Integer studentReplyRequired;
    private String title;
    private String content;
    private Integer priority;
    private Integer status;
    private Long assigneeId;
    private String aiSummary;
    private String aiCategory;
    private Integer rating;
    private String evaluation;
    private LocalDateTime createTime;
    private LocalDateTime acceptTime;
    private LocalDateTime finishTime;
    private LocalDateTime evaluateTime;
    private LocalDateTime deleteTime;
    private LocalDateTime studentReplyTime;
    private LocalDateTime updateTime;
}
