package org.cardanofoundation.tokenmetadata.registry.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CIP-26 (offchain metadata) flow.
 * Requires:
 * - PostgreSQL running
 * - API application started with TOKEN_METADATA_SYNC_JOB=true
 * - Test token registry git repo at GITHUB_TMP_FOLDER/GITHUB_PROJECT_NAME
 */
public class Cip26IntegrationIT extends BaseIntegrationIT {

    private static final String FULL_TOKEN_SUBJECT = "a0b1c2d3e4f5a0b1c2d3e4f5a0b1c2d3e4f5a0b1c2d3e4f5a0b1c2d354455354544f4b454e";
    private static final String MINIMAL_TOKEN_SUBJECT = "b1c2d3e4f5a0b1c2d3e4f5a0b1c2d3e4f5a0b1c2d3e4f5a0b1c2d3e44d494e544f4b454e";
    private static final String UNKNOWN_SUBJECT = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffff556e6b6e6f776e";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        waitForApiReady();
        waitForSyncComplete();
    }

    private static void waitForSyncComplete() {
        await().atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .until(() -> {
                    var response = restTemplate.getForEntity(
                            API_BASE_URL + "/metadata/" + FULL_TOKEN_SUBJECT, String.class);
                    return response.getStatusCode() == HttpStatus.OK;
                });
    }

    @Test
    void v1_queryKnownSubject_shouldReturnAllProperties() throws Exception {
        var response = restTemplate.getForEntity(
                API_BASE_URL + "/metadata/" + FULL_TOKEN_SUBJECT, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        var json = objectMapper.readTree(response.getBody());
        assertEquals(FULL_TOKEN_SUBJECT, json.get("subject").asText());
        assertEquals("Test Token Full", json.get("name").get("value").asText());
        assertEquals("TSTF", json.get("ticker").get("value").asText());
        assertEquals("A test token with all properties for integration testing", json.get("description").get("value").asText());
        assertEquals("https://test.cardanofoundation.org", json.get("url").get("value").asText());
        assertEquals("6", json.get("decimals").get("value").asText());
    }

    @Test
    void v1_queryUnknownSubject_shouldReturnNoContent() {
        var response = restTemplate.getForEntity(
                API_BASE_URL + "/metadata/" + UNKNOWN_SUBJECT, String.class);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void v2_queryKnownSubject_shouldReturnWithSource() throws Exception {
        var response = restTemplate.getForEntity(
                API_BASE_URL + "/api/v2/subjects/" + FULL_TOKEN_SUBJECT, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        var json = objectMapper.readTree(response.getBody());
        var subject = json.get("subject");
        assertEquals(FULL_TOKEN_SUBJECT, subject.get("subject").asText());

        var metadata = subject.get("metadata");
        assertEquals("Test Token Full", metadata.get("name").get("value").asText());
        assertEquals("CIP_26", metadata.get("name").get("source").asText());
    }

    @Test
    void v2_queryUnknownSubject_shouldReturn404() {
        var response = restTemplate.getForEntity(
                API_BASE_URL + "/api/v2/subjects/" + UNKNOWN_SUBJECT, String.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void v2_queryMinimalToken_shouldReturnOnlyNameAndDescription() throws Exception {
        var response = restTemplate.getForEntity(
                API_BASE_URL + "/api/v2/subjects/" + MINIMAL_TOKEN_SUBJECT, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        var json = objectMapper.readTree(response.getBody());
        var metadata = json.get("subject").get("metadata");
        assertEquals("Test Token Minimal", metadata.get("name").get("value").asText());
        assertEquals("A minimal test token with only required properties", metadata.get("description").get("value").asText());
        assertTrue(metadata.get("ticker") == null || metadata.get("ticker").isNull());
    }

    @Test
    void v2_batchQuery_shouldReturnOnlyExistingSubjects() throws Exception {
        var requestBody = String.format(
                "{\"subjects\": [\"%s\", \"%s\"], \"properties\": []}",
                FULL_TOKEN_SUBJECT, UNKNOWN_SUBJECT);

        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var request = new HttpEntity<>(requestBody, headers);

        var response = restTemplate.postForEntity(
                API_BASE_URL + "/api/v2/subjects/query", request, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        var json = objectMapper.readTree(response.getBody());
        var subjects = json.get("subjects");
        assertEquals(1, subjects.size());
        assertEquals(FULL_TOKEN_SUBJECT, subjects.get(0).get("subject").asText());
    }

    @Test
    void v2_queryWithPropertyFilter_shouldReturnOnlyRequestedProperties() throws Exception {
        var response = restTemplate.getForEntity(
                API_BASE_URL + "/api/v2/subjects/" + FULL_TOKEN_SUBJECT + "?property=name", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        var json = objectMapper.readTree(response.getBody());
        var metadata = json.get("subject").get("metadata");
        assertNotNull(metadata.get("name"));
        assertEquals("Test Token Full", metadata.get("name").get("value").asText());
    }
}
