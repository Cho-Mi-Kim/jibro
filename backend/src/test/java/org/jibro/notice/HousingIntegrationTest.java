package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.jibro.notice.HousingModels.*;
import java.time.LocalDate;
import java.util.*;
import org.jibro.auth.AuthService;
import org.jibro.global.common.ApiException;
import org.jibro.preparation.PreparationService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest @ActiveProfiles("test") @Transactional
class HousingIntegrationTest {
    @Autowired NoticeService notices;
    @Autowired PreparationService preparations;
    @Autowired AuthService auth;
    String owner,other;
    UUID notice;
    @BeforeEach void setup(){
        owner=createUser();other=createUser();
        notice=insert("서울","공공임대");notices.samplePublish(notice,"PASS");
    }
    String createUser(){String email=UUID.randomUUID()+"@example.invalid";auth.signup("테스트",email,"test-password-123");return auth.login(email,"test-password-123").user().id();}
    UUID insert(String region,String type){return notices.insert(UUID.randomUUID().toString(),"테스트 청년 공고","테스트 기관",region,type,
            LocalDate.now().minusDays(1),LocalDate.now().plusDays(30),LocalDate.now(),SampleData.content());}
    @Test void filterOrWithinAndAcrossAndHideUnreviewedGuide(){
        UUID second=insert("경기","공공임대");notices.samplePublish(second,"HOLD");
        UUID excluded=insert("서울","주거비 지원");notices.samplePublish(excluded,"PASS");
        insert("서울","공공임대");
        var page=notices.search(List.of("서울","경기"),List.of("공공임대"),List.of("OPEN"),"테스트","deadline",0,20);
        assertEquals(2,page.totalElements());
        assertTrue(notices.notice(second).questions().isEmpty());assertFalse(notices.notice(second).canStart());
        assertThrows(ApiException.class,()->preparations.start(owner,second,1));
    }
    @Test void ownerIsolationIdempotentStartRevisionAndProgress(){
        var p=preparations.start(owner,notice,1);UUID id=UUID.fromString(p.id);
        assertEquals(p.id,preparations.start(owner,notice,1).id);
        assertEquals(404,assertThrows(ApiException.class,()->preparations.get(other,id)).status);
        p=preparations.answers(owner,id,1,Map.of("residence",Answer.UNKNOWN,"house",Answer.UNKNOWN));
        assertEquals(0,p.progress);assertEquals(2,p.warnings.size());
        assertEquals(409,assertThrows(ApiException.class,()->preparations.answers(owner,id,1,Map.of())).status);
        p=preparations.answers(owner,id,2,Map.of("residence",Answer.YES,"house",Answer.NO));
        assertEquals("NOT_APPLICABLE",p.documents.get(1).necessity);
        p=preparations.document(owner,id,p.documents.get(0).id,3,DocumentState.READY,"서버에 저장한 메모");
        p=preparations.schedule(owner,id,"apply",4,true);assertEquals(100,p.progress);assertEquals(5,p.houseStage);
        assertEquals("서버에 저장한 메모",preparations.get(owner,id).documents.get(0).note);
    }
    @Test void changedContentPreservesSnapshotAndRejectsEdits(){
        var p=preparations.start(owner,notice,1);UUID id=UUID.fromString(p.id);
        notices.updateContent(notice,1,SampleData.content());
        var preserved=preparations.get(owner,id);assertEquals(1,preserved.version);assertEquals("CHANGE_REVIEW",preserved.guideState);
        assertEquals(p.questions,preserved.questions);
        assertEquals(409,assertThrows(ApiException.class,()->preparations.answers(owner,id,1,Map.of())).status);
        assertEquals(404,assertThrows(ApiException.class,()->notices.notice(notice)).status);
    }
    @Test void reviewPublishesBasicAndGuideSeparatelyAndAudits(){
        var review=notices.review(notice,owner,1,"PASS","UNSUPPORTED","준비 규칙 지원 범위 밖");
        assertEquals("UNSUPPORTED",review.notice().guideState());assertFalse(notices.notice(notice).canStart());
        notices.review(notice,owner,2,"HOLD","HOLD","원문 확인 필요");
        assertEquals(404,assertThrows(ApiException.class,()->notices.notice(notice)).status);
    }
    @Test void contentRejectsInvalidRuleAndUnsafeLink(){
        var c=SampleData.content();
        var bad=new Content(c.summary(),new Evidence("위험 링크","javascript:alert(1)","2026-10-30"),c.attachments(),c.questions(),c.documents(),c.schedules());
        assertThrows(IllegalArgumentException.class,()->notices.updateContent(notice,1,bad));
    }
}
