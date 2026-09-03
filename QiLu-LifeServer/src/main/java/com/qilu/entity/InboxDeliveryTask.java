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
@TableName("inbox_delivery_task")
public class InboxDeliveryTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String taskNo;
    private String monthKey;
    private Long messageId;
    private String targetType;
    private String targetValue;
    /** MQ publishing state. It is intentionally independent from delivery state. */
    private String publishStatus;
    private Integer publishAttempts;
    private LocalDateTime nextPublishTime;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private LocalDateTime lastPublishTime;
    private String lastPublishError;
    /** User-copy expansion state. */
    private String deliveryStatus;
    private Integer deliveryAttempts;
    private LocalDateTime nextDeliveryTime;
    private String lastDeliveryError;
    /** Optimistic-lock version used when multiple relay instances compete. */
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
