package com.qilu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user_info")
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    //涓婚敭锛岀敤鎴穒d

    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    //鍩庡競鍚嶇О

    private String city;

    //涓汉浠嬬粛锛屼笉瑕佽秴杩?28涓瓧绗?

    private String introduce;



    //鎬у埆锛?锛氱敺锛?锛氬コ

    private Boolean gender;

    //鐢熸棩

    private LocalDate birthday;

    //绉垎

    private Integer credits;

    //浼氬憳绾у埆锛?~9绾?0浠ｈ〃鏈紑閫氫細鍛?

    private Boolean level;

    //鍒涘缓鏃堕棿

    private LocalDateTime createTime;

    //鏇存柊鏃堕棿

    private LocalDateTime updateTime;


}
