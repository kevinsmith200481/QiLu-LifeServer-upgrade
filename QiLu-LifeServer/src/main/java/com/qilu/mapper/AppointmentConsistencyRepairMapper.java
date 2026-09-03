package com.qilu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qilu.entity.AppointmentConsistencyRepair;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface AppointmentConsistencyRepairMapper extends BaseMapper<AppointmentConsistencyRepair> {

    /**
     * The unique order/type key makes creating the repair intent idempotent.
     */
    @Insert("INSERT IGNORE INTO appointment_consistency_repair "
            + "(order_id, user_id, slot_id, repair_type, status, release_redis_quota, attempts, next_retry_time) "
            + "VALUES (#{orderId}, #{userId}, #{slotId}, 'CANCEL_REDIS_RELEASE', 'PENDING', "
            + "#{releaseRedisQuota}, 0, NOW())")
    int insertCancelRedisRepair(@Param("orderId") Long orderId,
                                @Param("userId") Long userId,
                                @Param("slotId") Long slotId,
                                @Param("releaseRedisQuota") boolean releaseRedisQuota);
}
