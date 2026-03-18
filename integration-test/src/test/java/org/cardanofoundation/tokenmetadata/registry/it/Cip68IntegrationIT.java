package org.cardanofoundation.tokenmetadata.registry.it;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CIP-68 (on-chain metadata) flow.
 * Requires:
 * - PostgreSQL running
 * - Yaci devnet running (yaci-store on port 8080, admin on port 10000)
 * - API application started and connected to the devnet node
 *
 * Flow: mint CIP-68 FT on devnet -> yaci-store indexes UTXO -> event listener
 * parses datum -> metadata_reference_nft table -> API query returns metadata
 */
public class Cip68IntegrationIT extends BaseIntegrationIT {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TOKEN_NAME = "IntTestFT";
    private static final String TOKEN_DESCRIPTION = "Integration test fungible token";
    private static final String TOKEN_TICKER = "ITFT";
    private static final int TOKEN_DECIMALS = 6;

    private static Cip68TestMinter.MintResult mintResult;

    @BeforeAll
    static void setUp() throws Exception {
        waitForApiReady();

        // Mint a CIP-68 FT on the devnet
        Cip68TestMinter minter = new Cip68TestMinter(new BFBackendService(YACI_STORE_URL, "Dummy"), restTemplate);
        mintResult = minter.mintCip68FungibleToken(
                TOKEN_NAME, TOKEN_DESCRIPTION, TOKEN_TICKER, TOKEN_DECIMALS, 1_000_000);

        // Wait for the API to index the minted token
        String subject = mintResult.policyId() + mintResult.assetNameHex();
        waitForCip68Indexed(subject);
    }

    private static void waitForCip68Indexed(String subject) {
        log.info("Waiting for CIP-68 token to be indexed (subject={}) ...", subject);
        await().atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofSeconds(3))
                .ignoreExceptions()
                .until(() -> {
                    ResponseEntity<String> response = restTemplate.getForEntity(
                            API_BASE_URL + "/api/v2/subjects/" + subject, String.class);
                    if (response.getStatusCode() == HttpStatus.OK) {
                        JsonNode json = objectMapper.readTree(response.getBody());
                        JsonNode metadata = json.get("subject").get("metadata");
                        boolean indexed = metadata.get("name") != null
                                && "CIP_68".equals(metadata.get("name").get("source").asText());
                        log.info("CIP-68 index poll: status={}, indexed={}", response.getStatusCode(), indexed);
                        return indexed;
                    }
                    log.info("CIP-68 index poll: status={}, not ready yet", response.getStatusCode());
                    return false;
                });
        log.info("CIP-68 token indexed successfully.");
    }

    @Test
    void v2_queryCip68Subject_shouldReturnOnChainMetadata() throws Exception {
        String subject = mintResult.policyId() + mintResult.assetNameHex();

        ResponseEntity<String> response = restTemplate.getForEntity(
                API_BASE_URL + "/api/v2/subjects/" + subject, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode json = objectMapper.readTree(response.getBody());
        JsonNode subjectNode = json.get("subject");
        assertEquals(subject, subjectNode.get("subject").asText());

        JsonNode metadata = subjectNode.get("metadata");
        assertEquals(TOKEN_NAME, metadata.get("name").get("value").asText());
        assertEquals("CIP_68", metadata.get("name").get("source").asText());
        assertEquals(TOKEN_DESCRIPTION, metadata.get("description").get("value").asText());
        assertEquals("CIP_68", metadata.get("description").get("source").asText());
        assertEquals(TOKEN_TICKER, metadata.get("ticker").get("value").asText());
        assertEquals(TOKEN_DECIMALS, metadata.get("decimals").get("value").asInt());
    }

    @Test
    void v2_queryCip68Subject_withCipsDetails_shouldReturnStandards() throws Exception {
        String subject = mintResult.policyId() + mintResult.assetNameHex();

        ResponseEntity<String> response = restTemplate.getForEntity(
                API_BASE_URL + "/api/v2/subjects/" + subject + "?show_cips_details=true", String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode json = objectMapper.readTree(response.getBody());
        JsonNode standards = json.get("subject").get("standards");
        assertNotNull(standards, "standards block should be present when show_cips_details=true");

        JsonNode cip68 = standards.get("cip68");
        assertNotNull(cip68, "cip68 standard should be present");
        assertEquals(TOKEN_NAME, cip68.get("name").asText());
        assertEquals(TOKEN_DESCRIPTION, cip68.get("description").asText());
    }

    @Test
    void v2_queryCip68Subject_withCip26Priority_shouldStillReturnCip68Data() throws Exception {
        // When CIP_26 is higher priority, CIP-68 data should still be returned if no CIP-26 data exists
        String subject = mintResult.policyId() + mintResult.assetNameHex();

        ResponseEntity<String> response = restTemplate.getForEntity(
                API_BASE_URL + "/api/v2/subjects/" + subject + "?query_priority=CIP_26&query_priority=CIP_68",
                String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode json = objectMapper.readTree(response.getBody());
        JsonNode metadata = json.get("subject").get("metadata");
        // Since there's no CIP-26 data for this token, CIP-68 data should be used
        assertEquals(TOKEN_NAME, metadata.get("name").get("value").asText());
        assertEquals("CIP_68", metadata.get("name").get("source").asText());

        // Query priority should reflect the requested order
        JsonNode queryPriority = json.get("queryPriority");
        assertEquals("CIP_26", queryPriority.get(0).asText());
        assertEquals("CIP_68", queryPriority.get(1).asText());
    }
}
