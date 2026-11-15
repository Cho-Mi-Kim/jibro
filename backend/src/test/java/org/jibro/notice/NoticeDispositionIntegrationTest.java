package org.jibro.notice;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.jibro.collection.LhNoticeClient;
import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
@SpringBootTest @ActiveProfiles("test") @Transactional
class NoticeDispositionIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired NoticeDispositionService decisions;
    @Autowired NoticeQueryRepository queries;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate db;
    LhNoticeClient.Item item(String title, String url, String fingerprint) {
        return new LhNoticeClient.Item(LhNoticeClient.sourceKey(url), title, "서울", "행복주택", url,
            LocalDate.now(), fingerprint.repeat(64), false);
    }
    @Test void closureSurvivesRecollectionWithoutInventingDatesOrOfficialEvidence() {
        String title = "manual-close-" + UUID.randomUUID();
        String url = "https://apply.lh.or.kr/example?case=" + UUID.randomUUID();
        UUID id = notices.importSource(item(title,url,"a"));
        notices.samplePublish(id,"HOLD");
        String content = notices.entity(id).content;
        decisions.apply(id,notices.entity(id).revision,"CLOSE","사용자 요청: 과거 공고 종료 처리");
        notices.importSource(item(title,url,"b"));
        notices.samplePublish(id,"HOLD");
        em.flush();
        var n = notices.entity(id);
        assertTrue(n.serviceClosed); assertFalse(n.sourceClosed);
        assertNull(n.startDate); assertNull(n.endDate); assertEquals(content,n.content);
        assertEquals("CLOSED",notices.notice(id).status()); assertFalse(notices.notice(id).canStart());
        assertTrue(notices.applicationClosed(id));
        assertEquals(0,queries.search(List.of(),List.of(),List.of(),title,"active",0,20,Instant.now()).totalElements());
        assertEquals(1,queries.search(List.of(),List.of(),List.of("CLOSED"),title,"active",0,20,Instant.now()).totalElements());
        assertEquals(0,queries.search(List.of(),List.of(),List.of("UNKNOWN"),title,"active",0,20,Instant.now()).totalElements());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM notice_service_decision WHERE notice_id=? AND origin='USER_REQUEST'",Integer.class,id));
        assertThrows(ApiException.class,()->decisions.apply(id,0,"CLOSE","stale"));
    }
    @Test void exclusionBlocksListingAndDirectDetailAndReviewQueue() {
        String title = "excluded-" + UUID.randomUUID();
        UUID id = notices.importSource(item(title,"https://apply.lh.or.kr/example?case="+UUID.randomUUID(),"a"));
        notices.samplePublish(id,"PASS");
        decisions.apply(id,notices.entity(id).revision,"EXCLUDE","사용자 요청: 기업체 선정 모집 제외"); em.flush();
        for (var statuses : List.of(List.<String>of(),List.of("UNKNOWN"),List.of("CLOSED"),List.of("OPEN")))
            assertEquals(0,queries.search(List.of(),List.of(),statuses,title,"active",0,20,Instant.now()).totalElements());
        assertThrows(ApiException.class,()->notices.notice(id)); assertTrue(notices.applicationClosed(id));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM notice n WHERE n.id=? AND "+AutomaticReviewEligibility.NOT_CLOSED_SQL,Integer.class,id));
    }
    @Test void importsExcludeNonResidentialWithoutDuplicateAudit() {
        String url="https://apply.lh.or.kr/example?case="+UUID.randomUUID();
        String title="가정어린이집 운영예정자 모집";
        UUID id=notices.importSource(item(title,url,"a")); notices.samplePublish(id,"PASS");
        notices.importSource(item(title,url,"b")); em.flush();
        assertTrue(notices.entity(id).serviceExcluded);
        assertThrows(ApiException.class,()->notices.notice(id));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM notice_service_decision WHERE notice_id=? AND origin='COLLECTION_SCOPE'",Integer.class,id));
    }
}
