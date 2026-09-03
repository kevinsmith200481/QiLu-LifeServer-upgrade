package com.qilu.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.ai.api.dto.CampusAssistantResponse;
import com.qilu.ai.api.dto.CampusAssistantSourceDTO;
import com.qilu.dto.ai.AiMemoryBuildResult;
import com.qilu.entity.AiMessage;
import com.qilu.service.IAiSessionMemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EnabledIfSystemProperty(named = "acceptance.ai-memory-stage-c", matches = "true")
class AiConversationMemoryIntegrationTest {

    private static final long SESSION_ID = 994001L;
    private static final long USER_ID = 994000L;
    private static final long USER_MESSAGE_ID = 994101L;
    private static final long ASSISTANT_MESSAGE_ID = 994102L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IAiSessionMemoryService memoryService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void prepare() {
        cleanup();
        jdbcTemplate.update(
                "INSERT INTO ai_session (id,user_id,title,scene,pinned,status,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,NOW(),NOW())",
                SESSION_ID, USER_ID, "stage-c-memory", "campus_assistant", 0, 1);
        jdbcTemplate.update(
                "INSERT INTO ai_message "
                        + "(id,session_id,turn_id,user_id,role,content,intent,metadata,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,NOW())",
                USER_MESSAGE_ID, SESSION_ID, "turn-stage-c-001", USER_ID,
                "user", "stage-c-question", null, null);
        jdbcTemplate.update(
                "INSERT INTO ai_message "
                        + "(id,session_id,turn_id,user_id,role,content,intent,metadata,create_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,NOW())",
                ASSISTANT_MESSAGE_ID, SESSION_ID, "turn-stage-c-001", USER_ID,
                "assistant", "stage-c-answer", "ticket_status",
                "{\"sources\":[{\"type\":\"ticket\",\"id\":12,"
                        + "\"statusText\":\"private-state\",\"snippet\":\"private-body\"}]}" );
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM ai_session_memory WHERE session_id=?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM ai_message WHERE session_id=?", SESSION_ID);
        jdbcTemplate.update("DELETE FROM ai_session WHERE id=?", SESSION_ID);
    }

    @Test
    void rebuildsWrongSchemaFromOwnedMysqlTurnsAndPersistsOnlyControlledFields() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO ai_session_memory "
                        + "(session_id,user_id,schema_version,last_processed_message_id,rolling_summary,"
                        + "entities_json,summary_source,summary_status,version,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,NOW(),NOW())",
                SESSION_ID, USER_ID, "1", 0L, "stale",
                "{\"tickets\":[{\"id\":99,\"lastSeenTurnId\":\"old\","
                        + "\"lastSeenMessageId\":1,\"status\":\"must-reject\"}],"
                        + "\"appointments\":[],\"servicePoints\":[]}",
                "deterministic", "ready", 0L);

        AiMemoryBuildResult result = memoryService.buildMemory(SESSION_ID, USER_ID);

        assertThat(result.getDiagnostics().getResolutionSource()).isEqualTo("mysql_rebuild");
        assertThat(result.getMemory().getEntities().getTickets()).extracting("id").containsExactly(12L);
        assertThat(result.getMemory().getLastProcessedMessageId()).isEqualTo(ASSISTANT_MESSAGE_ID);

        String stored = jdbcTemplate.queryForObject(
                "SELECT entities_json FROM ai_session_memory WHERE session_id=?",
                String.class,
                SESSION_ID);
        JsonNode json = objectMapper.readTree(stored);
        assertThat(json.path("tickets").get(0).path("id").longValue()).isEqualTo(12L);
        assertThat(json.toString()).doesNotContain(
                "private-state", "private-body", "statusText", "snippet", "must-reject");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT schema_version FROM ai_session_memory WHERE session_id=?",
                String.class,
                SESSION_ID)).isEqualTo("2");
    }

    @Test
    void rejectsCrossUserBuildWithoutChangingStoredOwnership() {
        jdbcTemplate.update(
                "INSERT INTO ai_session_memory "
                        + "(session_id,user_id,schema_version,last_processed_message_id,rolling_summary,"
                        + "entities_json,summary_source,summary_status,version,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,NOW(),NOW())",
                SESSION_ID, USER_ID, "2", ASSISTANT_MESSAGE_ID, "",
                emptyEntitiesJson(), "deterministic", "ready", 3L);

        AiMemoryBuildResult result = memoryService.buildMemory(SESSION_ID, USER_ID + 1);

        assertThat(result.getDiagnostics().getDegraded()).isTrue();
        assertThat(result.getDiagnostics().getDegradedReason()).isEqualTo("MEMORY_IDENTITY_INVALID");
        assertThat(result.getMemory().getEntities().getTickets()).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM ai_session_memory WHERE session_id=?",
                Long.class,
                SESSION_ID)).isEqualTo(USER_ID);
    }

    @Test
    void optimisticUpdateIsIdempotentAndVersionIsMonotonic() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO ai_session_memory "
                        + "(session_id,user_id,schema_version,last_processed_message_id,rolling_summary,"
                        + "entities_json,summary_source,summary_status,version,create_time,update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,NOW(),NOW())",
                SESSION_ID, USER_ID, "2", 0L, "",
                emptyEntitiesJson(), "deterministic", "ready", 0L);
        CampusAssistantSourceDTO source = new CampusAssistantSourceDTO();
        source.setType("appointment");
        source.setId(21L);
        source.setStatusText("private-state");
        CampusAssistantResponse response = new CampusAssistantResponse();
        response.setSources(Collections.singletonList(source));
        AiMessage assistant = new AiMessage()
                .setId(ASSISTANT_MESSAGE_ID)
                .setSessionId(SESSION_ID)
                .setUserId(USER_ID)
                .setTurnId("turn-stage-c-001")
                .setRole("assistant");

        memoryService.updateAfterAssistantMessage(
                SESSION_ID, USER_ID, "turn-stage-c-001", assistant, response);
        memoryService.updateAfterAssistantMessage(
                SESSION_ID, USER_ID, "turn-stage-c-001", assistant, response);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM ai_session_memory WHERE session_id=?",
                Long.class,
                SESSION_ID)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_processed_message_id FROM ai_session_memory WHERE session_id=?",
                Long.class,
                SESSION_ID)).isEqualTo(ASSISTANT_MESSAGE_ID);
        String stored = jdbcTemplate.queryForObject(
                "SELECT entities_json FROM ai_session_memory WHERE session_id=?",
                String.class,
                SESSION_ID);
        JsonNode json = objectMapper.readTree(stored);
        assertThat(json.path("appointments").get(0).path("id").longValue()).isEqualTo(21L);
        assertThat(json.toString()).doesNotContain("private-state", "statusText");
    }

    private String emptyEntitiesJson() {
        return "{\"tickets\":[],\"appointments\":[],\"servicePoints\":[],"
                + "\"pendingActionDraft\":null}";
    }
}
