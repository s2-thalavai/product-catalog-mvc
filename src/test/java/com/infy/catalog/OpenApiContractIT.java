package com.infy.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checklist: "spec exported and diffed in CI". Writes /v3/api-docs/v1 to target/contract/openapi-v1.json
 * and compares it (keys sorted, server URL removed) with src/test/resources/contract/openapi-v1.json.
 * Intended, backward-compatible change? Run scripts/update-contract.sh and commit the baseline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractIT {

    private static final Path OUTPUT = Path.of("target", "contract", "openapi-v1.json");
    private static final Path BASELINE = Path.of("src", "test", "resources", "contract", "openapi-v1.json");
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    @LocalServerPort
    private int port;

    @Test
    void publishedSpecMatchesBaseline() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs/v1")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String published = canonical(response.body());
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, published);

        assumeTrue(Files.exists(BASELINE),
                "No OpenAPI baseline yet: run scripts/update-contract.sh and commit src/test/resources/contract/");
        assertThat(published)
                .as("The OpenAPI contract changed. If intended and backward compatible, run scripts/update-contract.sh")
                .isEqualTo(canonical(Files.readString(BASELINE)));
    }

    static String canonical(String json) throws Exception {
        JsonNode tree = CANONICAL.readTree(json);
        if (tree.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("servers");   // contains the random port
        }
        return CANONICAL.writeValueAsString(CANONICAL.treeToValue(tree, Object.class)) + "\n";
    }
}
