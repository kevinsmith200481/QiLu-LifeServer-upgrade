package com.qilu.mapper;

import com.qilu.entity.InboxUserMessage;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class InboxUserMessageSqlProvider {

    public String batchInsert(Map<String, Object> params) {
        String table = (String) params.get("table");
        @SuppressWarnings("unchecked")
        List<InboxUserMessage> list = (List<InboxUserMessage>) params.get("list");
        String values = list.stream()
                .map(item -> "(#{list[" + list.indexOf(item) + "].monthKey}, #{list[" + list.indexOf(item)
                        + "].messageId}, #{list[" + list.indexOf(item) + "].messageNo}, #{list[" + list.indexOf(item)
                        + "].userId}, #{list[" + list.indexOf(item) + "].messageType}, 0, 0, 0, NOW(), NOW())")
                .collect(Collectors.joining(","));
        return "INSERT IGNORE INTO `" + table + "` "
                + "(month_key, message_id, message_no, user_id, message_type, read_status, star_status, deleted, create_time, update_time) "
                + "VALUES " + values;
    }

    public String updateReadStatus(@Param("table") String table,
                                   @Param("userId") Long userId,
                                   @Param("messageIds") List<Long> messageIds,
                                   @Param("readStatus") Integer readStatus) {
        return "UPDATE `" + table + "` SET read_status = #{readStatus}, "
                + "read_time = CASE WHEN #{readStatus} = 1 THEN NOW() ELSE NULL END, update_time = NOW() "
                + "WHERE user_id = #{userId} AND deleted = 0 AND message_id IN "
                + buildInClause(messageIds, "messageIds");
    }

    public String updateStarStatus(@Param("table") String table,
                                   @Param("userId") Long userId,
                                   @Param("messageIds") List<Long> messageIds,
                                   @Param("starStatus") Integer starStatus) {
        return "UPDATE `" + table + "` SET star_status = #{starStatus}, update_time = NOW() "
                + "WHERE user_id = #{userId} AND deleted = 0 AND message_id IN "
                + buildInClause(messageIds, "messageIds");
    }

    public String deleteUserMessages(@Param("table") String table,
                                     @Param("userId") Long userId,
                                     @Param("messageIds") List<Long> messageIds) {
        return "UPDATE `" + table + "` SET deleted = 1, update_time = NOW() "
                + "WHERE user_id = #{userId} AND deleted = 0 AND message_id IN "
                + buildInClause(messageIds, "messageIds");
    }

    private String buildInClause(List<Long> ids, String paramName) {
        String values = "";
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                values += ",";
            }
            values += "#{" + paramName + "[" + i + "]}";
        }
        return "(" + values + ")";
    }
}
