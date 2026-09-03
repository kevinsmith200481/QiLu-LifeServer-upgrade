package com.qilu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("operation_log")
public class OperationLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String userRole;
    private String module;
    private String operation;
    private String requestMethod;
    private String requestUri;
    private String classMethod;
    private String params;
    private String businessType;
    @JsonSerialize(using = ToStringSerializer.class)
    private Long businessId;
    private String beforeStatus;
    private String afterStatus;
    private String remarkSummary;
    private Integer success;
    private String errorMsg;
    private Long costTime;
    private String ip;
    private LocalDateTime createTime;
}
