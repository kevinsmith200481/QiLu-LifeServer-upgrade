package com.qilu.ai.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.ai.api.dto.CampusAssistantRequest;
import com.qilu.ai.api.dto.CampusAssistantResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CampusMemoryContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requestFixtureRoundTripsWithoutChangingMemory() throws Exception {
        CampusAssistantRequest request = readFixture(
                "campus-memory-request.json",
                CampusAssistantRequest.class);

        assertThat(request.getTurnId()).isEqualTo("turn-contract-002");
        assertThat(request.getMemory()).isNotNull();
        assertThat(request.getMemory().getSchemaVersion()).isEqualTo("2");
        assertThat(request.getMemory().getEntities().getTickets()).extracting("id").containsExactly(12L);

        String encoded = objectMapper.writeValueAsString(request);
        CampusAssistantRequest decoded = objectMapper.readValue(encoded, CampusAssistantRequest.class);
        JsonNode before = objectMapper.readTree(encoded).get("memory");
        JsonNode after = objectMapper.readTree(objectMapper.writeValueAsString(decoded)).get("memory");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void responseFixtureRoundTripsBodyFreeDiagnostics() throws Exception {
        CampusAssistantResponse response = readFixture(
                "campus-memory-response.json",
                CampusAssistantResponse.class);

        assertThat(response.getMemoryDiagnostics()).isNotNull();
        assertThat(response.getMemoryDiagnostics().getRecentTurnCount()).isEqualTo(1);
        assertThat(response.getMemoryDiagnostics().getEntityTypes()).containsExactly("ticket");

        JsonNode diagnostics = objectMapper.readTree(
                objectMapper.writeValueAsString(response.getMemoryDiagnostics()));
        assertThat(diagnostics.has("question")).isFalse();
        assertThat(diagnostics.has("rollingSummary")).isFalse();
        assertThat(diagnostics.has("ticketId")).isFalse();
    }

    @Test
    void legacyRequestWithoutNewFieldsRemainsCompatible() throws Exception {
        CampusAssistantRequest request = objectMapper.readValue(
                "{\"question\":\"legacy request\",\"history\":[]}",
                CampusAssistantRequest.class);

        assertThat(request.getQuestion()).isEqualTo("legacy request");
        assertThat(request.getTurnId()).isNull();
        assertThat(request.getMemory()).isNull();
    }

    private <T> T readFixture(String name, Class<T> type) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertThat(input).as("Missing contract fixture: %s", name).isNotNull();
            return objectMapper.readValue(input, type);
        }
    }
}
