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
@TableName("ai_message")
public class AiMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** 同一轮 user/assistant 消息共享该标识；历史消息允许为空。 */
    private String turnId;
    private Long userId;
    private String role;
    private String content;
    private String intent;
    private String metadata;
    private LocalDateTime createTime;
}
