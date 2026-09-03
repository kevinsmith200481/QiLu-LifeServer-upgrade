package com.qilu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qilu.entity.StationComment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface StationCommentMapper extends BaseMapper<StationComment> {

    @Select("SELECT root.* " +
            "FROM station_comment root " +
            "LEFT JOIN ( " +
            "  SELECT root_id, SUM(like_count) AS reply_like_count " +
            "  FROM station_comment " +
            "  WHERE station_id = #{stationId} AND parent_id <> 0 AND status = 1 " +
            "  GROUP BY root_id " +
            ") reply_stats ON reply_stats.root_id = root.id " +
            "WHERE root.station_id = #{stationId} AND root.parent_id = 0 AND root.status = 1 " +
            "ORDER BY (root.like_count + root.reply_count * 2 + COALESCE(reply_stats.reply_like_count, 0)) DESC, " +
            "root.like_count DESC, root.reply_count DESC, root.floor_no ASC, root.id ASC " +
            "LIMIT #{offset}, #{limit}")
    List<StationComment> selectHotRootComments(@Param("stationId") Long stationId,
                                                @Param("offset") int offset,
                                                @Param("limit") int limit);

    @Select("<script>" +
            "SELECT station_id AS stationId, COUNT(*) AS commentCount " +
            "FROM station_comment " +
            "WHERE status = 1 AND station_id IN " +
            "<foreach collection='stationIds' item='stationId' open='(' separator=',' close=')'>#{stationId}</foreach> " +
            "GROUP BY station_id" +
            "</script>")
    List<Map<String, Object>> selectCommentCountsByStationIds(@Param("stationIds") List<Long> stationIds);
}
