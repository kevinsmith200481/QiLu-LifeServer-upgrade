package com.qilu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qilu.entity.AppointmentOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AppointmentOrderMapper extends BaseMapper<AppointmentOrder> {

    /**
     * Performs the only legal user cancellation transition. A zero result is an
     * idempotent replay or a non-reserved order and must not release quota.
     */
    @Update("UPDATE appointment_order SET status = 2, cancel_time = #{cancelTime} "
            + "WHERE id = #{orderId} AND user_id = #{userId} AND status = 1")
    int cancelReservedOrder(@Param("orderId") Long orderId,
                            @Param("userId") Long userId,
                            @Param("cancelTime") LocalDateTime cancelTime);
}
