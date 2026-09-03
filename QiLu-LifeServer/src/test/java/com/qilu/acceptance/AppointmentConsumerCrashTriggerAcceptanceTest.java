package com.qilu.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Starts the real HTTP and Redis Stream consumer path, then deliberately lets
 * the acceptance JVM terminate after the database commit and before XACK.
 *
 * <p>This test is successful only when Surefire reports the forked JVM exit
 * code 91. The orchestration script verifies that outcome and starts the
 * recovery process in a separate JVM.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18082",
                "qilu.appointment.consumer.enabled=true"
        }
)
@EnabledIfSystemProperty(named = "acceptance.appointment-crash-trigger", matches = "true")
class AppointmentConsumerCrashTriggerAcceptanceTest {

    static final long SLOT_ID = 9_801_001L;
    static final String TOKEN = "phase1-consumer-crash";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void processTerminatesAfterDatabaseCommitAndBeforeStreamAck() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, TOKEN);

        ResponseEntity<String> response = restTemplate.exchange(
                "/appointment-order/reserve/" + SLOT_ID,
                HttpMethod.POST,
                new HttpEntity<>(null, headers),
                String.class
        );

        assertTrue(response.getStatusCode().is2xxSuccessful(), "reservation HTTP request must be accepted");
        assertTrue(response.getBody() != null && response.getBody().contains("\"success\":true"),
                "reservation response must contain a successful order id");

        // The consumer thread calls Runtime.halt(91). Reaching this timeout
        // means the destructive acceptance switch did not fire as designed.
        Thread.sleep(Duration.ofSeconds(30).toMillis());
        fail("acceptance JVM did not halt after appointment persistence");
    }
}
