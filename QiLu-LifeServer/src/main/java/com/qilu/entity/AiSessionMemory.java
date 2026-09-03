package com.qilu.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** MySQL 会话 Memory 记录；entitiesJson 只能保存受控实体引用。 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("ai_session_memory")
public class AiSessionMemory implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId("session_id")
    private Long sessionId;
    private Long userId;
    private String schemaVersion;
    private Long lastProcessedMessageId;
    /** 最近一次成功模型摘要覆盖到的 assistant 消息位置。 */
    private Long lastModelSummaryMessageId;
    private String rollingSummary;
    private String entitiesJson;
    private String summarySource;
    private String summaryStatus;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
