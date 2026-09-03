package com.qilu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qilu.entity.AppointmentSlot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface AppointmentSlotMapper extends BaseMapper<AppointmentSlot> {

    /**
     * Deducts one quota only while the slot is active, unexpired and available.
     * The caller must treat exactly one affected row as success.
     */
    @Update("UPDATE appointment_slot SET available_quota = available_quota - 1 "
            + "WHERE id = #{slotId} AND status = 1 AND end_time > NOW() AND available_quota > 0")
    int deductAppointmentQuota(@Param("slotId") Long slotId);

    /**
     * Releases one quota without allowing the stored value to exceed total quota.
     */
    @Update("UPDATE appointment_slot SET available_quota = available_quota + 1 "
            + "WHERE id = #{slotId} AND available_quota < total_quota")
    int releaseAppointmentQuota(@Param("slotId") Long slotId);
}
