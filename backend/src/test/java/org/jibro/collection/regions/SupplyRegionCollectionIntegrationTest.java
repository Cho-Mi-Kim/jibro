package org.jibro.collection.regions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.*;
import org.jibro.collection.LhNoticeClient;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.notice.NoticeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SupplyRegionCollectionIntegrationTest {
    @Autowired SupplyRegionRepository regions;
    @Autowired NoticeService notices;
    @Autowired JdbcTemplate db;
    @Autowired EntityManager entities;

    @Test
    void collectedOfferMatchesEitherRegionOnceAndDropsReplacedEvidence() throws Exception {
        String url = OfficialSupplyRegionsTest.URL;
        var item = new LhNoticeClient.Item(LhNoticeClient.sourceKey(url),"공급지역 연동 검증","대구광역시 외","임대",
            url,LocalDate.now(),"e".repeat(64));
        UUID id = notices.importSource(item);
        notices.samplePublish(id,"PASS");
        entities.flush();
        var http = mock(AttachmentFingerprintClient.class);
        var collector = new SupplyRegionCollector(regions,http);
        collector.process(id,item);
        db.update("update notice_supply_scope set next_check_at='1900-01-01' where notice_id=?",id);
        var job=regions.claim().orElseThrow();
        assertEquals(id,job.id());
        var header=OfficialSupplyRegionsTest.FORM+"<section><h3>공급정보</h3>";
        when(http.html(url)).thenReturn(header+"<li>소재지 : 경상북도 경산시</li></section>",
            header+"<li>소재지 : 경기도 부천시</li></section>", header+"<li>모집지역 : 전국</li></section>");
        collector.refresh(job);
        for (var selected : List.of(List.of("대구광역시"),List.of("경상북도"),List.of("대구광역시","경상북도"))) {
            assertEquals(1,notices.search(selected,List.of(),List.of(),item.title(),"active",0,1).totalElements());
        }
        assertEquals("대구·경북", notices.notice(id).region());
        assertEquals("대구·경북", notices.search(List.of(),List.of(),List.of(),item.title(),"active",0,1).items().get(0).region());
        assertTrue(notices.filters().get("regions").contains("경상북도"));
        db.update("update notice_supply_scope set next_check_at='1900-01-01' where notice_id=?",id);
        collector.refresh(regions.claim().orElseThrow());
        assertEquals(0,notices.search(List.of("경상북도"),List.of(),List.of(),item.title(),"active",0,1).totalElements());
        assertEquals(1,notices.search(List.of("경기도"),List.of(),List.of(),item.title(),"active",0,1).totalElements());
        db.update("update notice_supply_scope set next_check_at='1900-01-01' where notice_id=?",id);
        collector.refresh(regions.claim().orElseThrow());
        assertEquals("전국", notices.notice(id).region());
        assertEquals("전국", notices.search(List.of("전국"),List.of(),List.of(),item.title(),"active",0,1).items().get(0).region());
        collector.close();
    }
}
