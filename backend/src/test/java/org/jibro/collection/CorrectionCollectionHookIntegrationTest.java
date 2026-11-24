package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.*;

import org.jibro.auth.AuthService;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {"app.collection.automatic-correction-enabled=true",
        "app.collection.correction-initial-delay-ms=86400000", "app.collection.max-pages=1", "app.collection.category-codes=06"})
@ActiveProfiles("test")
class CorrectionCollectionHookIntegrationTest {
    @Autowired
    CollectionService collections;
    @Autowired
    AuthService auth;
    @Autowired
    JdbcTemplate db;
    @MockitoBean
    LhNoticeClient lh;
    @MockitoBean
    AttachmentFingerprintClient http;
    String actor;
    String sourceKey;

    @AfterEach
    void cleanup() {
        if (actor != null) {
            db.update("DELETE FROM collection_run WHERE actor_id=?", UUID.fromString(actor));
            db.update("DELETE FROM app_user WHERE id=?", UUID.fromString(actor));
        }
        if (sourceKey != null) db.update("DELETE FROM notice WHERE source_key=?", sourceKey);
    }

    @Test
    void successfulCollectionDurablyEnqueuesOfficialIdentityWithoutBlockingOnRemoteVerification() throws Exception {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("수집 연결 검사", email, "collection-test-password");
        actor = db.queryForObject("SELECT id::text FROM app_user WHERE email=?", String.class, email);
        String url = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=991155009"
                + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07&mi=1026";
        var item = new LhNoticeClient.Item(LhNoticeClient.sourceKey(url), "[정정공고] 신규 수집 공고", "서울", "국민임대", url, LocalDate.now(), "b".repeat(64));
        sourceKey = item.key();
        when(lh.configured()).thenReturn(true);
        when(lh.fetch(eq(1), any(CollectionQueryPlan.Query.class))).thenReturn(new LhNoticeClient.Batch(List.of(item), 1));
        assertEquals("SUCCEEDED", collections.collect(actor, 1).status());
        assertEquals("PENDING", db.queryForObject("SELECT state FROM notice_correction_check WHERE official_url=?", String.class, url));
        verifyNoInteractions(http);
        // Re-collecting the same official source must not produce duplicate work or reset backoff.
        db.update("UPDATE notice_correction_check SET result_code='RETRY_REQUIRED',next_attempt_at=now()+interval '1 hour' WHERE official_url=?", url);
        collections.collect(actor, 1);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM notice_correction_check WHERE official_url=?", Integer.class, url));
        assertEquals("RETRY_REQUIRED", db.queryForObject("SELECT result_code FROM notice_correction_check WHERE official_url=?", String.class, url));
    }
}
