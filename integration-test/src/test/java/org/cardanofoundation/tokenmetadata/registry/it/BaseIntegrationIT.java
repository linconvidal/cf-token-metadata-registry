package org.cardanofoundation.tokenmetadata.registry.it;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.awaitility.Awaitility.await;

public abstract class BaseIntegrationIT {

    protected static final String API_BASE_URL = System.getenv().getOrDefault("API_BASE_URL", "http://localhost:8081");
    protected static final String YACI_STORE_URL = System.getenv().getOrDefault("YACI_STORE_URL", "http://localhost:8080/api/v1/");

    protected static final RestTemplate restTemplate;

    static {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        restTemplate = new RestTemplate(factory);
    }

    protected static void waitForApiReady() {
        await().atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .ignoreExceptions()
                .until(() -> {
                    var response = restTemplate.getForEntity(API_BASE_URL + "/actuator/health", String.class);
                    return response.getStatusCode().is2xxSuccessful();
                });
    }
}
