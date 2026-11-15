package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.IOException;
import java.time.*;
import java.util.*;

import org.jibro.auth.AuthService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "app.collection.category-codes=06")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CollectionResponseArchiveIntegrationTest {
    @Autowired
    CollectionService collections;
    @Autowired
    CollectionRunStore runs;
    @Autowired
    CollectionResponseService archive;
    @Autowired
    AuthService auth;
    @Autowired
    JdbcTemplate db;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @MockitoBean
    LhNoticeClient client;
    String actor;
    LhNoticeClient real;

    @BeforeEach
    void setup() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("원본 기록 검사", email, "archive-test-password");
        actor = db.queryForObject("SELECT id::text FROM app_user WHERE email=?", String.class, email);
        when(client.configured()).thenReturn(true);
        real = new LhNoticeClient("test-private-service-key", json);
    }

    @Test
    void identicalJsonIsRetainedOnceButEveryRunHasItsOwnReceipt() throws Exception {
        var batch = accepted("{\"SS_CODE\":\"Y\",\"ALL_CNT\":0,\"providerExtra\":{\"unknown\":\"retain\"},\"ServiceKey\":\"test-private-service-key\"}");
        when(client.fetch(anyInt(), any(CollectionQueryPlan.Query.class))).thenReturn(batch);
        UUID first = UUID.fromString(collections.collect(actor, 1).id());
        UUID second = UUID.fromString(collections.collect(actor, 1).id());
        var receipt = archive.list(first).get(0);
        assertEquals("ACCEPTED", receipt.outcome());
        assertEquals("JSON", receipt.bodyStatus());
        assertEquals(1, receipt.page());
        assertEquals(1, receipt.runAttempt());
        assertTrue(receipt.redacted());
        assertEquals(receipt.sha256(), archive.list(second).get(0).sha256());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM collection_response_payload WHERE sha256=?", Integer.class, receipt.sha256()));
        var response = archive.find(first, UUID.fromString(receipt.id()));
        assertTrue(response.bodyJson().contains("providerExtra"));
        assertFalse(response.bodyJson().contains("test-private-service-key"));
        assertThrows(org.jibro.global.common.ApiException.class, () -> archive.find(second, UUID.fromString(receipt.id())));
    }

    @Test
    void retryKeepsFailedTransportAndOriginalQueryDateAlongsideSuccessfulAttempt() throws Exception {
        LocalDate date = LocalDate.of(2020, 1, 1);
        var query = new CollectionQueryPlan.Query("06", LocalDate.of(2000, 1, 1), date);
        UUID run = runs.enqueueManual(actor, 1, Instant.parse("2020-01-01T12:00:00Z"));
        when(client.fetch(1, query)).thenThrow(new IOException("temporary failure")).thenReturn(accepted("{\"SS_CODE\":\"Y\",\"ALL_CNT\":0}"));
        collections.processDue();
        assertEquals("RETRY_WAIT", runs.view(run).status());
        db.update("UPDATE collection_run SET next_attempt_at=now()-interval '1 second' WHERE id=?", run);
        collections.processDue();
        var receipts = archive.list(run);
        assertEquals(2, receipts.size());
        assertEquals("TRANSPORT_FAILED", receipts.get(0).outcome());
        assertEquals("NO_RESPONSE", receipts.get(0).bodyStatus());
        assertNull(receipts.get(0).sha256());
        assertEquals("ACCEPTED", receipts.get(1).outcome());
        assertEquals(2, receipts.get(1).runAttempt());
        assertTrue(receipts.stream().allMatch(r -> r.page() == 1 && r.queryDate().equals(date)));
        assertEquals("SUCCEEDED", runs.view(run).status());
    }

    @Test
    void invalidPaginationRetainsJsonButDoesNotBecomeSuccessfulCollection() throws Exception {
        when(client.fetch(anyInt(), any(CollectionQueryPlan.Query.class))).thenReturn(accepted("{\"SS_CODE\":\"Y\",\"ALL_CNT\":300}"));
        var run = collections.collect(actor, 1);
        assertEquals("FAILED", run.status());
        var receipt = archive.list(UUID.fromString(run.id())).get(0);
        assertEquals("REJECTED", receipt.outcome());
        assertNotNull(receipt.sha256());
        assertTrue(archive.find(UUID.fromString(run.id()), UUID.fromString(receipt.id())).bodyJson().contains("300"));
    }

    @Test
    void invalidProviderResponseIsArchivedAndOnlyAdministratorsCanReadBodies() throws Exception {
        var failure = assertThrows(SourceResponse.Rejected.class,
            () -> real.accept(200, "{\"SS_CODE\":\"N\",\"reason\":\"bad query\"}", null, Instant.now()));
        when(client.fetch(anyInt(), any(CollectionQueryPlan.Query.class))).thenThrow(failure);
        UUID run = UUID.fromString(collections.collect(actor, 1).id());
        var receipt = archive.list(run).get(0);
        assertEquals("REJECTED", receipt.outcome());
        String path = "/api/v1/admin/collections/" + run + "/responses/" + receipt.id();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk()).andExpect(jsonPath("bodyJson").value(failure.response().bodyJson()));
    }

    private LhNoticeClient.Batch accepted(String body) throws Exception {
        return real.accept(200, body, null, Instant.now());
    }
}
