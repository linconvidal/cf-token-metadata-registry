package org.cardanofoundation.tokenmetadata.registry.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for health and sync status endpoints.
 * Verifies that /health reports correct sync state and /actuator/health is available.
 */
public class SyncStatusIntegrationIT extends BaseIntegrationIT {

    private static final String KNOWN_SUBJECT = "a0b1c2d3e4f5a0b1c2d3e4f5a0b1c2d3e4f5a0b1c2d3e4f5a0b1c2d354455354544f4b454e";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        waitForApiReady();
        waitForSyncComplete();
    }

    private static void waitForSyncComplete() {
        log.info("Waiting for sync to complete before running sync status tests ...");
        await().atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .until(() -> {
                    var response = restTemplate.getForEntity(
                            API_BASE_URL + "/metadata/" + KNOWN_SUBJECT, String.class);
                    log.info("Sync status test - sync poll: status={}", response.getStatusCode());
                    return response.getStatusCode() == HttpStatus.OK;
                });
        log.info("Sync complete, running sync status tests.");
    }

    @Test
    void health_afterSync_shouldReportSyncedAndDone() throws Exception {
        var response = restTemplate.getForEntity(API_BASE_URL + "/health", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        var json = objectMapper.readTree(response.getBody());
        assertTrue(json.get("synced").asBoolean(), "synced should be true after initial sync");
        assertEquals("Sync done", json.get("syncStatus").asText());
    }

    @Test
    void actuatorHealth_shouldBeUp() throws Exception {
        var response = restTemplate.getForEntity(API_BASE_URL + "/actuator/health", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        var json = objectMapper.readTree(response.getBody());
        assertEquals("UP", json.get("status").asText());
    }
}
