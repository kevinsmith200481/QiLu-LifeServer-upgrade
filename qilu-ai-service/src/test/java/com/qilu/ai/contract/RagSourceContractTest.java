package com.qilu.ai.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.ai.api.dto.CampusAssistantResponse;
import com.qilu.ai.api.dto.CampusAssistantSourceDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagSourceContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void hybridSourceEvidenceRoundTripsWithoutDroppingLegacyFields() throws Exception {
        String payload = "{\"answer\":\"ok\",\"intent\":\"printing\",\"sources\":[{"
                + "\"type\":\"knowledge\",\"knowledgeId\":9,\"title\":\"打印规则\","
                + "\"snippet\":\"实际命中片段\",\"score\":0.9,\"source\":\"ai_knowledge\","
                + "\"knowledgeVersion\":\"knowledge-v1\",\"indexVersion\":\"index-v1\","
                + "\"chunkIndexes\":[1,2],\"retrievers\":[\"milvus\",\"bm25\"],"
                + "\"fusionScore\":0.0325,\"retrieverScores\":{\"milvus\":0.9,\"bm25\":2.1},"
                + "\"normalizedRetrieverScores\":{\"milvus\":0.9,\"bm25\":0.6774}}]}";

        CampusAssistantResponse response = objectMapper.readValue(payload, CampusAssistantResponse.class);
        CampusAssistantSourceDTO source = response.getSources().get(0);

        assertThat(source.getKnowledgeId()).isEqualTo(9L);
        assertThat(source.getTitle()).isEqualTo("打印规则");
        assertThat(source.getKnowledgeVersion()).isEqualTo("knowledge-v1");
        assertThat(source.getIndexVersion()).isEqualTo("index-v1");
        assertThat(source.getChunkIndexes()).containsExactly(1, 2);
        assertThat(source.getRetrievers()).containsExactly("milvus", "bm25");
        assertThat(source.getFusionScore()).isEqualTo(0.0325);
        assertThat(source.getRetrieverScores()).containsEntry("bm25", 2.1);
        assertThat(source.getNormalizedRetrieverScores()).containsEntry("milvus", 0.9);
    }

    @Test
    void legacySourceWithoutHybridFieldsRemainsCompatible() throws Exception {
        String payload = "{\"answer\":\"ok\",\"intent\":\"general\",\"sources\":[{"
                + "\"type\":\"knowledge\",\"knowledgeId\":1,\"title\":\"旧来源\","
                + "\"knowledgeVersion\":\"legacy-v1\"}]}";

        CampusAssistantResponse response = objectMapper.readValue(payload, CampusAssistantResponse.class);
        CampusAssistantSourceDTO source = response.getSources().get(0);

        assertThat(source.getKnowledgeId()).isEqualTo(1L);
        assertThat(source.getKnowledgeVersion()).isEqualTo("legacy-v1");
        assertThat(source.getIndexVersion()).isNull();
        assertThat(source.getChunkIndexes()).isNull();
        assertThat(source.getRetrievers()).isNull();
        assertThat(source.getRetrieverScores()).isNull();
        assertThat(source.getNormalizedRetrieverScores()).isNull();
    }
}
