package org.jibro.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;

import org.jibro.auth.AuthService;
import org.jibro.notice.NoticeService;
import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CollectionIntegrationTest {
    @Autowired
    CollectionService collections;
    @Autowired
    NoticeService notices;
    @Autowired
    AuthService auth;

    @Test
    void missingKeyCreatesHonestRunAndNoSuccess() {
        String email = UUID.randomUUID() + "@example.invalid";
        auth.signup("검증 사용자", email, "test-password-123");
        String actor = auth.login(email, "test-password-123").user().id();
        var run = collections.collect(actor, 1);
        assertEquals("NOT_CONFIGURED", run.status());
        assertTrue(collections.list().stream().anyMatch(r -> r.id().equals(run.id())));
    }

    @Test
    void repeatedImportIsIdempotentAndChangedSourceReturnsToReview() {
        String key = "test-" + UUID.randomUUID();
        var item = new LhNoticeClient.Item(key, "공고", "서울", "임대", "https://apply.lh.or.kr/example", LocalDate.now(), "one");
        UUID id = notices.importSource(item);
        assertEquals(id, notices.importSource(item));
        assertEquals(404, assertThrows(ApiException.class, () -> notices.notice(id)).status);
        notices.samplePublish(id, "HOLD");
        assertEquals(1, notices.notice(id).version());
        notices.importSource(new LhNoticeClient.Item(key, "변경 공고", "서울", "임대", item.url(), item.published(), "two"));
        assertEquals(404, assertThrows(ApiException.class, () -> notices.notice(id)).status);
        assertEquals(2, notices.reviews().stream().filter(r -> r.id().equals(id.toString())).findFirst().orElseThrow().notice().version());
    }
}

