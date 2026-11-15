package org.jibro.collection.attachments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jibro.collection.LhNoticeClient;
import org.jibro.notice.ApplicationPeriod;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.HousingModels.DocumentTemplate;
import org.jibro.notice.NoticeService;
import org.jibro.notice.SampleData;
import org.jibro.preparation.PreparationSnapshots;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OfficialFileCollectionIntegrationTest {
    @Autowired OfficialFileRepository inventory;
    @Autowired NoticeService notices;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager em;
    @Autowired ObjectMapper json;

    @Test
    void collectedNoticeBecomesEligibleAfterDateReviewAndLinksARealMemberNameAcrossViews() throws Exception {
        // CI's PostgreSQL runs in UTC, while application periods use Korean dates.
        db.execute("SET LOCAL TIME ZONE 'UTC'");
        LocalDate today = LocalDate.now(ApplicationPeriod.SEOUL);
        String url = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=876543210&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07";
        var item = new LhNoticeClient.Item(LhNoticeClient.sourceKey(url), "서류 양식 검증", "전국", "임대",
            url, today, "8".repeat(64));
        UUID id = notices.importSource(item);
        em.flush();
        var http = mock(AttachmentFingerprintClient.class);
        var collector = new OfficialFileCollector(inventory, http, json);
        collector.process(id, item);
        db.update("UPDATE notice_file_inventory SET next_check_at='1900-01-01' WHERE notice_id=?", id);
        assertFalse(inventory.claim().filter(j -> j.id().equals(id)).isPresent());

        var sample = SampleData.content();
        var doc = new DocumentTemplate("file", "자산 보유 사실확인서", null, "작성", "LH", null, sample.evidence());
        var content = new Content(sample.summary(), sample.evidence(), List.of(), List.of(), List.of(doc), List.of());
        db.update("""
            UPDATE notice SET basic_review='PASS',guide_review='PASS',start_date=?,end_date=?,
                content=cast(? as jsonb) WHERE id=?
            """, today.plusDays(2), today.plusDays(4), json.writeValueAsString(content), id);
        em.clear();
        var job = inventory.claim().orElseThrow();
        assertEquals(id, job.id());
        String file = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=32";
        String member = "붙임/[양식3]자산보유사실확인서.hwp";
        when(http.html(url)).thenReturn("""
            <form id="wrtancViewForm"><input name="panId" value="876543210"></form>
            <a href="javascript:fileDownLoad('32')">제출서류.zip</a>
            """);
        when(http.zipMembers(file)).thenReturn(List.of(member));
        collector.refresh(job);
        var notice = notices.notice(id);
        assertEquals("UPCOMING", notice.status());
        assertEquals(file, notice.attachments().get(0).url());
        assertTrue(notice.documentTemplates().get(0).issueLabel().contains(member));
        assertEquals(file, PreparationSnapshots.fresh(notice, content).preparation().documents.get(0).issueUrl);

        db.update("UPDATE notice_file_inventory SET next_check_at='1900-01-01' WHERE notice_id=?", id);
        var previous = inventory.claim().orElseThrow();
        db.update("UPDATE notice SET source_closed=true WHERE id=?", id);
        em.clear();
        inventory.finish(previous, "{}", false);
        assertEquals("CLOSED", notices.notice(id).status());
        assertTrue(notices.notice(id).attachments().isEmpty());
        assertTrue(db.queryForObject("select jsonb_exists(manifest,'files') from notice_file_inventory where notice_id=?", Boolean.class, id));
    }
}
