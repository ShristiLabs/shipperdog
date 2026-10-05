package com.shipperdog.api.apiconventions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end coverage of the API conventions (issue #8): the single
 * problem+json envelope across every status class, the cursor pagination
 * contract (walk without gaps or duplicates, bounds rejected, never
 * clamped), and the payload serialization rules (ISO-8601 UTC timestamps,
 * UPPER_SNAKE enums). The organizations surface is the demo endpoint of
 * each convention.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration,classpath:db/seed",
            "shipperdog.auth.jwt-secret=test-jwt-secret-0123456789abcdef0123456789abcdef"
        })
@Testcontainers
class ApiConventionsIntegrationTest {

    private static final String DEV_ADMIN_EMAIL = "dev@shipperdog.local";
    private static final String DEV_ADMIN_PASSWORD = "shipperdog-dev-admin";
    private static final String PASSWORD = "a-perfectly-valid-password";
    private static final String ISO_8601_UTC = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @LocalServerPort
    int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // ------------------------------------------------------------------
    // Error envelope
    // ------------------------------------------------------------------

    @Test
    void unauthenticatedRequestsGetTheProblemEnvelope() {
        Recording response = get("/api/v1/auth/me", null);

        assertThat(response.status).as("missing bearer is a 401").isEqualTo(401);
        assertThat(response.contentType).startsWith("application/problem+json");
        assertThat(response.header(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        JsonNode body = response.body;
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(body.get("type").asText()).isEqualTo("https://api.shipperdog.dev/problems/unauthenticated");
        assertThat(body.get("detail").asText()).isEqualTo("Authentication is required.");
        assertThat(body.get("trace_id").asText())
                .as("trace_id echoes the correlation header")
                .isNotBlank();
    }

    @Test
    void targetLessPermissionDenialsStayOpaque403sWithTheEnvelope() throws Exception {
        String memberToken = registerOrglessUser("envelope.member@example.com");

        Recording response = post("/api/v1/organizations", memberToken, Map.of("name", "Denied Corp"));

        assertThat(response.status)
                .as("creation is platform-scoped: opaque 403, not concealment")
                .isEqualTo(403);
        assertThat(response.contentType).startsWith("application/problem+json");
        JsonNode body = response.body;
        assertThat(body.get("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.get("detail").asText()).isEqualTo("Access denied.");
        assertThat(body.has("error")).as("the legacy error-key shape is gone").isFalse();
    }

    @Test
    void routingAndParsingFaultsUseTheEnvelope() {
        Recording noRoute = get("/api/v1/does-not-exist", devToken());
        assertThat(noRoute.status).isEqualTo(404);
        assertThat(noRoute.body.get("code").asText()).isEqualTo("NOT_FOUND");

        Recording malformed = postRaw("/api/v1/auth/login", "not-json{{");
        assertThat(malformed.status).isEqualTo(400);
        assertThat(malformed.body.get("code").asText()).isEqualTo("INVALID_REQUEST");

        Recording wrongMethod = patch("/api/v1/organizations", devToken());
        assertThat(wrongMethod.status).isEqualTo(405);
        assertThat(wrongMethod.body.get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void concealedDenialsAndGenuineMissesShareOneShape() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Conceal Corp");
        String outsiderToken = registerOrglessUser("envelope.outsider@example.com");

        Recording concealed = get("/api/v1/organizations/" + orgId, outsiderToken);
        Recording genuine = get("/api/v1/organizations/" + UUID.randomUUID(), outsiderToken);

        assertThat(concealed.status).isEqualTo(404);
        assertThat(genuine.status).isEqualTo(404);
        for (String member : List.of("code", "title", "detail")) {
            assertThat(concealed.body.get(member).asText())
                    .as("concealed denial matches a genuine miss on %s", member)
                    .isEqualTo(genuine.body.get(member).asText());
        }
        assertThat(concealed.body.get("code").asText()).isEqualTo("ORGANIZATION_NOT_FOUND");
        // The audit trail, not the response, records what was denied.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_events WHERE event_type = 'ACCESS_DENIED' AND detail ->> 'uri' = ?",
                        Integer.class,
                        "/api/v1/organizations/" + orgId))
                .isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Cursor pagination
    // ------------------------------------------------------------------

    @Test
    void cursorWalkReturnsEveryItemExactlyOnce() throws Exception {
        String devToken = devToken();
        Set<UUID> created = new LinkedHashSet<>();
        for (int i = 0; i < 5; i++) {
            created.add(createOrganization(devToken, "Walk Org " + i));
        }

        Set<UUID> seen = new HashSet<>();
        String cursor = "";
        int pages = 0;
        while (true) {
            JsonNode page = getJson(
                    "/api/v1/organizations?limit=2" + (cursor.isEmpty() ? "" : "&cursor=" + cursor), devToken, 200);
            assertThat(page.get("items").size()).as("limit is honored").isLessThanOrEqualTo(2);
            page.get("items")
                    .forEach(item -> seen.add(UUID.fromString(item.get("id").asText())));
            pages++;
            if (!page.hasNonNull("next_cursor")) {
                break;
            }
            cursor = page.get("next_cursor").asText();
        }
        assertThat(seen).as("the walk covers every organization, no gaps").containsAll(created);
        assertThat(seen.size())
                .as("no organization appears twice")
                .isEqualTo(seen.stream().distinct().count());
        assertThat(pages).as("five organizations at limit=2 span three pages").isGreaterThanOrEqualTo(3);
    }

    @Test
    void cursorWalkSurvivesLimitChangesBetweenPages() throws Exception {
        String devToken = devToken();
        for (int i = 0; i < 4; i++) {
            createOrganization(devToken, "Mixed Walk " + i);
        }

        JsonNode first = getJson("/api/v1/organizations?limit=3", devToken, 200);
        assertThat(first.get("items").size()).isEqualTo(3);
        String cursor = first.get("next_cursor").asText();
        // The cursor is an absolute offset: switching the page size continues
        // the walk instead of restarting it.
        JsonNode second = getJson("/api/v1/organizations?limit=2&cursor=" + cursor, devToken, 200);
        assertThat(second.get("items").size()).isEqualTo(2);
    }

    @Test
    void paginationBoundsAreRejectedNotClamped() throws Exception {
        String devToken = devToken();
        for (String badLimit : new String[] {"0", "101", "-1", "zero"}) {
            Recording response = get("/api/v1/organizations?limit=" + badLimit, devToken);
            assertThat(response.status).as("limit=%s is rejected", badLimit).isEqualTo(400);
            assertThat(response.body.get("code").asText()).isEqualTo("INVALID_REQUEST");
        }
        Recording badCursor = get("/api/v1/organizations?cursor=%%%garbage", devToken);
        assertThat(badCursor.status).isEqualTo(400);
        assertThat(badCursor.body.get("code").asText()).isEqualTo("INVALID_CURSOR");
    }

    @Test
    void auditTrailPagesWithTheSameCursorContract() throws Exception {
        String devToken = devToken();

        JsonNode page = getJson("/api/v1/audit?limit=1", devToken, 200);
        assertThat(page.get("items").size()).isEqualTo(1);
        assertThat(page.hasNonNull("next_cursor"))
                .as("logins already seeded events, so another page follows")
                .isTrue();
    }

    // ------------------------------------------------------------------
    // Payload serialization rules
    // ------------------------------------------------------------------

    @Test
    void payloadsUseIso8601UtcTimestampsAndUpperSnakeEnums() throws Exception {
        String devToken = devToken();
        UUID orgId = createOrganization(devToken, "Serialized Corp");
        JsonNode org = getJson("/api/v1/organizations/" + orgId, devToken, 200);

        assertThat(org.get("createdAt").asText()).matches(ISO_8601_UTC);
        assertThat(org.get("updatedAt").asText()).matches(ISO_8601_UTC);
        assertThat(org.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode members = getJson("/api/v1/organizations/" + orgId + "/members?limit=10", devToken, 200);
        assertThat(members.get("items").get(0).get("role").asText()).isEqualTo("ORG_ADMIN");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String devToken() {
        JsonNode tokens = mapper.readTree(client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", DEV_ADMIN_EMAIL, "password", DEV_ADMIN_PASSWORD))
                .retrieve()
                .body(String.class));
        return tokens.get("accessToken").asText();
    }

    private String registerOrglessUser(String email) {
        JsonNode invitation = postJson("/api/v1/auth/invitations", devToken(), Map.of("email", email), 201);
        client.post()
                .uri("/api/v1/auth/register")
                .body(Map.of(
                        "invitationToken",
                        invitation.get("invitationToken").asText(),
                        "email",
                        email,
                        "password",
                        PASSWORD,
                        "fullName",
                        "Conventions User"))
                .retrieve()
                .body(String.class);
        JsonNode tokens = mapper.readTree(client.post()
                .uri("/api/v1/auth/login")
                .body(Map.of("email", email, "password", PASSWORD))
                .retrieve()
                .body(String.class));
        return tokens.get("accessToken").asText();
    }

    private UUID createOrganization(String token, String name) {
        JsonNode created = postJson("/api/v1/organizations", token, Map.of("name", name), 201);
        return UUID.fromString(created.get("id").asText());
    }

    private JsonNode postJson(String uri, String bearer, Map<String, Object> body, int expectedStatus) {
        Recording response = exchange(uri, bearer, "POST", body, null);
        assertThat(response.status).as("POST %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private JsonNode getJson(String uri, String bearer, int expectedStatus) {
        Recording response = get(uri, bearer);
        assertThat(response.status).as("GET %s", uri).isEqualTo(expectedStatus);
        return response.body;
    }

    private Recording get(String uri, String bearer) {
        return exchange(uri, bearer, "GET", null, null);
    }

    private Recording post(String uri, String bearer, Map<String, Object> body) {
        return exchange(uri, bearer, "POST", body, null);
    }

    private Recording patch(String uri, String bearer) {
        return exchange(uri, bearer, "PATCH", Map.of(), null);
    }

    private Recording postRaw(String uri, String rawBody) {
        return client.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .body(rawBody)
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readAllBytes();
                    return new Recording(
                            response.getStatusCode().value(),
                            response.getHeaders(),
                            response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                            bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
                });
    }

    private Recording exchange(String uri, String bearer, String method, Map<String, Object> body, String rawBody) {
        RestClient.RequestBodySpec spec =
                client.method(HttpMethod.valueOf(method)).uri(uri);
        if (bearer != null) {
            spec.headers(headers -> headers.setBearerAuth(bearer));
        }
        if (rawBody != null) {
            spec.body(rawBody);
        } else if (body != null) {
            spec.body(body);
        }
        return spec.exchange((request, response) -> {
            byte[] bytes = response.getBody().readAllBytes();
            return new Recording(
                    response.getStatusCode().value(),
                    response.getHeaders(),
                    response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                    bytes.length == 0 ? mapper.createObjectNode() : mapper.readTree(bytes));
        });
    }

    private record Recording(int status, HttpHeaders headers, String contentType, JsonNode body) {

        String header(String name) {
            return headers.getFirst(name);
        }
    }
}
