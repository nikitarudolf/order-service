package com.innowise.order_service.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.innowise.order_service.TestcontainersConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderControllerIntegrationTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final String USERS = "/api/v1/users";
    private static final String EMAIL = "alex+orders@example.com";
    private static final String USER = """
            {"id":7,"name":"Alex","surname":"Smith","birthDate":"2000-01-01","email":"alex+orders@example.com","active":true}
            """;
    private static final SecretKey KEY = Jwts.SIG.HS256.key().build();
    private static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

    static {
        WIREMOCK.start();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private CircuitBreaker userServiceCircuitBreaker;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("services.user-url", WIREMOCK::baseUrl);
        registry.add("jwt.secret", () -> Base64.getEncoder().encodeToString(KEY.getEncoded()));
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM orders");
        WIREMOCK.resetAll();
        userServiceCircuitBreaker.reset();
    }

    @Test
    void returnsOriginalSnapshotForReadsAndUpdateWhenUserServiceFails() throws Exception {
        String bearer = bearer(7L, "USER", "access");
        stubUser(bearer);
        long id = createOrder(bearer);
        long itemRowId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", Long.class, id);
        WIREMOCK.stubFor(WireMock.any(WireMock.anyUrl()).atPriority(1).willReturn(aResponse().withStatus(500)));

        mvc.perform(get(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(hasBuyerSnapshotAt("/user"));
        LocalDate created = jdbc.queryForObject("SELECT created_at::date FROM orders WHERE id = ?", LocalDate.class, id);
        mvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, bearer)
                        .param("statuses", "NEW").param("createdFrom", created.toString())
                        .param("createdTo", created.toString()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].order.id").value(id))
                .andExpect(hasBuyerSnapshotAt("/content/0/user"));
        mvc.perform(get(ORDERS + "/user/7").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].order.id").value(id))
                .andExpect(hasBuyerSnapshotAt("/content/0/user"));
        mvc.perform(patch(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":%d,"quantity":3}]}
                                """.formatted(catalogItemId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("NEW"))
                .andExpect(jsonPath("$.order.totalPrice").value(149.7))
                .andExpect(jsonPath("$.order.items[0].id").value(itemRowId))
                .andExpect(jsonPath("$.order.items[0].quantity").value(3))
                .andExpect(jsonPath("$.order.updatedAt").exists())
                .andExpect(hasBuyerSnapshotAt("/user"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class, id))
                .isEqualTo(1);
        mvc.perform(delete(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());
        mvc.perform(get(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.id").value(id));
        mvc.perform(patch(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("CANCELLED"));

        String admin = bearer(9L, "ADMIN", "access");
        mvc.perform(delete(ORDERS + "/{id}", Long.MAX_VALUE).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
        mvc.perform(delete(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        mvc.perform(delete(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
        mvc.perform(get(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
        mvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        assertThat(jdbc.queryForObject("SELECT deleted FROM orders WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class, id))
                .isEqualTo(1);
        WIREMOCK.verify(1, getRequestedFor(urlPathEqualTo(USERS + "/by-email"))
                .withQueryParam("email", equalTo(EMAIL))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(bearer)));
        assertThat(WIREMOCK.getAllServeEvents()).hasSize(1);
    }

    @Test
    void filtersByInclusiveDateRangeAndBothStatusesAcrossPages() throws Exception {
        long ownerId = 7L;
        String owner = bearer(ownerId, "USER", "access");
        LocalDate from = LocalDate.of(2026, 4, 10);
        LocalDate to = LocalDate.of(2026, 4, 12);
        insertOrder(ownerId, "NEW", from.minusDays(1).atTime(23, 59));
        long firstId = insertOrder(ownerId, "NEW", from.atStartOfDay());
        insertOrder(ownerId, "CANCELLED", from.plusDays(1).atTime(12, 0));
        long secondId = insertOrder(ownerId, "PAID", to.atTime(21, 30));
        insertOrder(ownerId, "PAID", to.plusDays(1).atStartOfDay());

        List<Long> expectedIds = List.of(firstId, secondId);
        for (int page = 0; page < expectedIds.size(); page++) {
            mvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, owner)
                            .param("createdFrom", from.toString()).param("createdTo", to.toString())
                            .param("statuses", "NEW", "PAID").param("size", "1")
                            .param("sort", "id,asc").param("page", Integer.toString(page)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(1))
                    .andExpect(jsonPath("$.content[0].order.id").value(expectedIds.get(page)))
                    .andExpect(jsonPath("$.page.number").value(page))
                    .andExpect(jsonPath("$.page.totalElements").value(2));
        }
    }

    @Test
    void patchEnforcesRolesAndConflictsWithoutPartialWrites() throws Exception {
        String owner = bearer(7L, "USER", "access");
        String admin = bearer(9L, "ADMIN", "access");
        stubUser(owner);
        long id = createOrder(owner);
        var originalItems = jdbc.queryForList("SELECT id, item_id, quantity FROM order_items WHERE order_id = ?", id);
        String items = """
                {"items":[{"itemId":%d,"quantity":3}]}
                """.formatted(catalogItemId());
        String invalidCombined = """
                {"status":"SHIPPED","items":[{"itemId":%d,"quantity":3}]}
                """.formatted(catalogItemId());

        mvc.perform(patch(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(invalidCombined))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT total_price FROM orders WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("99.80");
        assertThat(jdbc.queryForList("SELECT id, item_id, quantity FROM order_items WHERE order_id = ?", id))
                .isEqualTo(originalItems);

        mvc.perform(patch(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("PAID"))
                .andExpect(jsonPath("$.order.totalPrice").value(99.8))
                .andExpect(hasBuyerSnapshotAt("/user"));
        assertThat(jdbc.queryForList("SELECT id, item_id, quantity FROM order_items WHERE order_id = ?", id))
                .isEqualTo(originalItems);

        mvc.perform(patch(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content(items))
                .andExpect(status().isConflict());
        mvc.perform(patch(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(invalidCombined))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id)).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT total_price FROM orders WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("99.80");
        assertThat(jdbc.queryForList("SELECT id, item_id, quantity FROM order_items WHERE order_id = ?", id))
                .isEqualTo(originalItems);

        mvc.perform(patch(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer(8L, "USER", "access"))
                        .contentType(MediaType.APPLICATION_JSON).content(items))
                .andExpect(status().isForbidden());
    }

    @Test
    void patchMatchesDuplicateItemsByLineIdAndAddsAndRemovesRows() throws Exception {
        String owner = bearer(7L, "USER", "access");
        stubUser(owner);
        long keyboard = catalogItemId();
        long mouse = jdbc.queryForObject("SELECT id FROM items WHERE name = 'Mouse'", Long.class);
        long monitor = jdbc.queryForObject("SELECT id FROM items WHERE name = 'Monitor'", Long.class);
        var created = mvc.perform(post(ORDERS).header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"email":"%s","items":[{"itemId":%d,"quantity":1},{"itemId":%d,"quantity":2},
                                {"itemId":%d,"quantity":1}]}
                                """.formatted(EMAIL, keyboard, keyboard, mouse)))
                .andExpect(status().isCreated()).andReturn();
        long id = mapper.readTree(created.getResponse().getContentAsString()).at("/order/id").asLong();
        List<Long> keyboardRows = jdbc.queryForList(
                "SELECT id FROM order_items WHERE order_id = ? AND item_id = ? ORDER BY id", Long.class, id, keyboard);

        mvc.perform(patch(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, owner)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"items":[{"itemId":%d,"quantity":3},{"itemId":%d,"quantity":4},
                                {"itemId":%d,"quantity":1}]}
                                """.formatted(keyboard, keyboard, monitor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("NEW"))
                .andExpect(jsonPath("$.order.totalPrice").value(538.3));
        assertThat(jdbc.queryForList("SELECT id FROM order_items WHERE order_id = ? AND item_id = ? ORDER BY id",
                Long.class, id, keyboard)).containsExactlyElementsOf(keyboardRows);
        assertThat(jdbc.queryForList("SELECT quantity FROM order_items WHERE order_id = ? AND item_id = ? ORDER BY id",
                Integer.class, id, keyboard)).containsExactly(3, 4);
        assertThat(jdbc.queryForList("SELECT item_id FROM order_items WHERE order_id = ? ORDER BY id", Long.class, id))
                .containsExactly(keyboard, keyboard, monitor);
    }

    @Test
    void rejectsEmptyNullAndUnknownPatchFields() throws Exception {
        String owner = bearer(7L, "USER", "access");
        for (String payload : List.of("{}", "{\"status\":null}", "{\"items\":null}", "{\"items\":[]}",
                "{\"status\":\"CANCELLED\",\"items\":null}",
                "{\"status\":null,\"items\":[{\"itemId\":1,\"quantity\":1}]}",
                "{\"status\":\"CANCELLED\",\"id\":123}")) {
            mvc.perform(patch(ORDERS + "/{id}", 123).header(HttpHeaders.AUTHORIZATION, owner)
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void anotherUserCannotSeeOrderAndAdminCan() throws Exception {
        String owner = bearer(7L, "USER", "access");
        String stranger = bearer(8L, "USER", "access");
        String admin = bearer(9L, "ADMIN", "access");
        stubUser(owner);
        long id = createOrder(owner);

        mvc.perform(get(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, stranger))
                .andExpect(status().isForbidden());
        mvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mvc.perform(get(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.userId").value(7));
    }

    @Test
    void requiresAccessTokenAndValidBody() throws Exception {
        mvc.perform(get(ORDERS)).andExpect(status().isUnauthorized());
        mvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, bearer(7L, "USER", "refresh")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(ORDERS).header(HttpHeaders.AUTHORIZATION, bearer(7L, "USER", "access"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"invalid","items":[{"itemId":1,"quantity":0}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors['items[0].quantity']").exists());
    }

    @Test
    void keepsNullBuyerForAnOrderStoredWithoutSnapshot() throws Exception {
        Long id = jdbc.queryForObject("""
                INSERT INTO orders (user_id, status, total_price, created_at, updated_at)
                VALUES (7, 'NEW', 49.90, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class);
        String bearer = bearer(7L, "USER", "access");

        mvc.perform(get(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.userId").value(7))
                .andExpect(result -> assertThat(mapper.readTree(result.getResponse().getContentAsString()).at("/user"))
                        .isEqualTo(NullNode.getInstance()));
        mvc.perform(patch(ORDERS + "/{id}", id).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"itemId":%d,"quantity":1}]}
                                """.formatted(catalogItemId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.status").value("NEW"))
                .andExpect(result -> assertThat(mapper.readTree(result.getResponse().getContentAsString()).at("/user"))
                        .isEqualTo(NullNode.getInstance()));
        assertThat(WIREMOCK.getAllServeEvents()).isEmpty();
    }

    @Test
    void returnsNotFoundWithoutSavingWhenBuyerProfileIsMissing() throws Exception {
        String bearer = bearer(7L, "USER", "access");
        WIREMOCK.stubFor(WireMock.get(urlPathEqualTo(USERS + "/by-email"))
                .withQueryParam("email", equalTo(EMAIL))
                .willReturn(aResponse().withStatus(404)));

        mvc.perform(post(ORDERS).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isNotFound());

        assertNoOrdersOrPositions();
    }

    @Test
    void opensCircuitAfterUserServiceFailuresAndLeavesDatabaseUnchanged() throws Exception {
        String bearer = bearer(7L, "USER", "access");
        WIREMOCK.stubFor(WireMock.get(urlPathEqualTo(USERS + "/by-email"))
                .willReturn(aResponse().withStatus(500)));

        for (int i = 0; i < 6; i++) {
            mvc.perform(post(ORDERS).header(HttpHeaders.AUTHORIZATION, bearer)
                            .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                    .andExpect(status().isServiceUnavailable());
        }

        assertThat(userServiceCircuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        WIREMOCK.verify(5, getRequestedFor(urlPathEqualTo(USERS + "/by-email")));
        assertNoOrdersOrPositions();
    }

    private void assertNoOrdersOrPositions() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
    }

    private ResultMatcher hasBuyerSnapshotAt(String pointer) {
        return result -> assertThat(mapper.readTree(result.getResponse().getContentAsString()).at(pointer))
                .isEqualTo(mapper.readTree(USER));
    }

    private long insertOrder(long userId, String status, LocalDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO orders (user_id, status, total_price, created_at, updated_at)
                VALUES (?, ?, 49.90, ?, ?) RETURNING id
                """, Long.class, userId, status, createdAt, createdAt);
    }

    private long createOrder(String bearer) throws Exception {
        var result = mvc.perform(post(ORDERS).header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.order.totalPrice").value(99.8))
                .andExpect(jsonPath("$.order.createdAt").exists())
                .andExpect(hasBuyerSnapshotAt("/user"))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("order").path("id").asLong();
    }

    private String createBody() {
        return """
                {"email":"%s","items":[{"itemId":%d,"quantity":2}]}
                """.formatted(EMAIL, catalogItemId());
    }

    private long catalogItemId() {
        return jdbc.queryForObject("SELECT id FROM items WHERE name = 'Keyboard'", Long.class);
    }

    private void stubUser(String bearer) {
        WIREMOCK.stubFor(WireMock.get(urlPathEqualTo(USERS + "/by-email"))
                .withQueryParam("email", equalTo(EMAIL))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo(bearer))
                .willReturn(okJson(USER)));
    }

    private String bearer(Long userId, String role, String type) {
        return "Bearer " + Jwts.builder()
                .subject(userId.toString())
                .claim("role", role)
                .claim("type", type)
                .expiration(Date.from(Instant.now().plusSeconds(120)))
                .signWith(KEY)
                .compact();
    }
}
