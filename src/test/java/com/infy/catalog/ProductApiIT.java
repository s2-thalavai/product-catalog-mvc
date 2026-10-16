package com.infy.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infy.catalog.common.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application over real HTTP (embedded Tomcat, H2, Flyway, security, Thymeleaf),
 * driven like an external client would. Tests share the database, so each uses its own SKUs/category.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiIT {

    private static final String API = "/api/v1/products";
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final String WRITE_TOKEN = TestTokens.withScope("catalog.write");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper json;

    @Test
    void crudLifecycleWithEtags() throws Exception {
        String sku = sku();
        HttpResponse<String> created = send("POST", API, json(sku, "Desk Lamp", "it-crud", "1299.5", 10), Map.of());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        String path = URI.create(location).getPath();
        assertThat(location).matches("http://localhost:\\d+/api/v1/products/\\d+");
        assertThat(json.readTree(created.body()).get("price").decimalValue()).isEqualByComparingTo("1299.50");

        HttpResponse<String> read = get(path, "application/json", Map.of());
        String etag = read.headers().firstValue("ETag").orElseThrow();
        assertThat(get(path, "application/json", Map.of("If-None-Match", etag)).statusCode()).isEqualTo(304);

        HttpResponse<String> stale = send("PATCH", path, "{\"stock\":2}", Map.of("Content-Type", "application/merge-patch+json", "If-Match", "\"0-99\""));
        assertThat(stale.statusCode()).isEqualTo(412);
        assertThat(problem(stale).get("errorCode").asText()).isEqualTo("PRECONDITION_FAILED");

        HttpResponse<String> patched = send("PATCH", path, "{\"stock\":2,\"category\":\"\"}", Map.of("Content-Type", "application/merge-patch+json", "If-Match", etag));
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        JsonNode body = json.readTree(patched.body());
        assertThat(body.get("stock").asInt()).isEqualTo(2);
        assertThat(body.has("category")).isFalse();
        assertThat(patched.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(etag);

        HttpResponse<String> replaced = send("PUT", path, json(sku, "Desk Lamp v2", "it-crud", "999", 4), Map.of());
        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replaced.body()).get("name").asText()).isEqualTo("Desk Lamp v2");

        assertThat(send("DELETE", path, null, Map.of()).statusCode()).isEqualTo(204);
        assertThat(problem(get(path, "application/json", Map.of())).get("errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void searchFiltersSortsPaginatesAndLinks() throws Exception {
        String category = "it-search-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 1; i <= 7; i++) {
            send("POST", API, json(sku(), "Search Item " + i, category, String.valueOf(100 * i), i % 3 == 0 ? 0 : 5), Map.of());
        }
        String query = API + "?category=" + category + "&minPrice=200&inStock=true&sort=price,desc&size=2";

        HttpResponse<String> first = get(query, "application/json", Map.of());
        JsonNode page = json.readTree(first.body());
        assertThat(page.get("totalElements").asInt()).isEqualTo(4);   // 700, 500, 400, 200
        assertThat(names(page)).containsExactly("Search Item 7", "Search Item 5");
        assertThat(first.headers().firstValue("X-Total-Count")).contains("4");
        assertThat(String.join(",", first.headers().allValues("Link"))).contains("rel=\"next\"").contains("page=1");

        JsonNode second = json.readTree(get(query + "&page=1", "application/json", Map.of()).body());
        assertThat(names(second)).containsExactly("Search Item 4", "Search Item 2");
        assertThat(second.get("hasNext").asBoolean()).isFalse();

        assertThat(json.readTree(get(API + "?size=5000", "application/json", Map.of()).body()).get("size").asInt()).isEqualTo(100);
    }

    @Test
    void fourRepresentationsOfOneResource() throws Exception {
        assertThat(get(API + "/1", "application/json", Map.of()).body()).contains("\"sku\":\"ELEC-MOUSE-01\"");
        assertThat(get(API + "/1", "application/xml", Map.of()).body()).contains("<sku>ELEC-MOUSE-01</sku>");
        assertThat(get(API + "/1", "text/plain", Map.of()).body()).contains("sku:         ELEC-MOUSE-01");
        HttpResponse<String> html = get(API + "?category=books", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", Map.of());
        assertThat(html.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/html");
        assertThat(html.body()).contains("<form class=\"search\"").contains("Kubernetes in Practice");
        assertThat(get(API + "?format=xml", "application/json", Map.of()).body()).contains("<productPage>");
        assertThat(get(API, "*/*", Map.of()).headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
    }

    @Test
    void problemsInEveryFormat() throws Exception {
        String missing = API + "/987654321";
        assertThat(get(missing, "application/xml", Map.of()).body()).contains("urn:ietf:rfc:7807");
        assertThat(get(missing, "text/html", Map.of()).body()).contains("404 Product not found");
        assertThat(get(missing, "text/plain", Map.of()).body()).contains("[PRODUCT_NOT_FOUND]");
        assertThat(get("/api/v1/nothing", "application/json", Map.of()).statusCode()).isEqualTo(404);
    }

    @Test
    void securityAndCors() throws Exception {
        HttpResponse<String> anonymous = send("POST", API, json(sku(), "X", "it-sec", "1", 1), Map.of("Authorization", ""));
        assertThat(anonymous.statusCode()).isEqualTo(401);

        HttpResponse<String> readOnly = send("POST", API, json(sku(), "X", "it-sec", "1", 1),
                Map.of("Authorization", "Bearer " + TestTokens.withScope("catalog.read")));
        assertThat(readOnly.statusCode()).isEqualTo(403);

        HttpResponse<String> preflight = send("OPTIONS", API, null, Map.of("Origin", "http://localhost:3000",
                "Access-Control-Request-Method", "PATCH", "Authorization", ""));
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        HttpResponse<String> evil = get(API, "application/json", Map.of("Origin", "https://evil.example.com"));
        assertThat(evil.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void probesAndDocs() throws Exception {
        assertThat(get("/actuator/health/liveness", "application/json", Map.of()).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness", "application/json", Map.of()).statusCode()).isEqualTo(200);
        assertThat(get("/v3/api-docs/v1", "application/json", Map.of()).body()).contains("/api/v1/products/{id}");
    }

    // ------------------------------------------------------------------ helpers

    private HttpResponse<String> get(String path, String accept, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().header("Accept", accept);
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Writes default to JSON with the write token; headers override (an empty value removes one). */
    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        Map<String, String> all = new java.util.LinkedHashMap<>(Map.of(
                "Content-Type", "application/json", "Accept", "application/json", "Authorization", "Bearer " + WRITE_TOKEN));
        all.putAll(headers);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        all.forEach((name, value) -> {
            if (!value.isEmpty()) {
                request.header(name, value);
            }
        });
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode problem(HttpResponse<String> response) throws Exception {
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.get("traceId").asText()).isNotBlank();
        return problem;
    }

    private static String json(String sku, String name, String category, String price, int stock) {
        return "{\"sku\":\"" + sku + "\",\"name\":\"" + name + "\",\"category\":\"" + category
                + "\",\"price\":" + price + ",\"stock\":" + stock + "}";
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(item -> names.add(item.get("name").asText()));
        return names;
    }

    private static String sku() {
        return "IT-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(java.util.Locale.ROOT);
    }
}
