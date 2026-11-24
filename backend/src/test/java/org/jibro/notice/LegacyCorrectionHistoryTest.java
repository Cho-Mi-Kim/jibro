package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LegacyCorrectionHistoryTest {
    static final String OLD = OfficialCorrectionHistoryTest.OLD, NEXT = OfficialCorrectionHistoryTest.NEXT;

    static String url(String id) {
        return OfficialCorrectionHistoryTest.url(id);
    }

    static String original() {
        return OfficialCorrectionHistoryTest.page(false).replace(NEXT, OLD)
                .replace("var currPanKdCd = '02'", "var currPanKdCd = '01'")
                .replace("var panKdCd = '02'", "var panKdCd = '01'");
    }

    static String latest() {
        return OfficialCorrectionHistoryTest.page(true)
                .replace("<dl><dt>정정사유</dt><dd>접수 일정 안내 수정</dd></dl>", "")
                .replace("첨부파일명 정정\n상세 안내", "수정정보 : 주택관리번호 변경\n수정된 번호 안내");
    }

    static String history() {
        return OfficialCorrectionHistoryTest.history();
    }

    static OfficialCorrectionLink.Proof verify(String old, String latest, String history) {
        return OfficialCorrectionLink.verifyFamilyHistory(url(OLD), old, url(NEXT), latest, history);
    }

    @Test
    void explicitHistoryProvesLegacySelfReferencingOriginalAndRetainsCorrectionText() {
        var proof = verify(original(), latest(), history());
        assertEquals(OLD, proof.originalPanId());
        assertEquals(NEXT, proof.correctedPanId());
        assertNotNull(proof.historyHtmlSha256());
        assertEquals("수정정보 : 주택관리번호 변경\n수정된 번호 안내", proof.reason());
        assertEquals(proof.reason(), verify(OfficialCorrectionHistoryTest.page(false), latest(), history()).reason());
        assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verifyLegacyRootHistory(
                url(OLD), OfficialCorrectionHistoryTest.page(false), url(NEXT), latest(), history()));
    }

    @Test
    void ordinaryAdditionalRecruitmentOrVacanciesAreNotEvidenceOfCorrection() {
        for (String title : new String[]{"잔여세대 추가모집", "입주자 추가모집", "자격완화 재공고"}) {
            String namedOriginal = original().replace("원공고 제목", title);
            String namedLatest = latest().replace("정정 제목", title);
            assertThrows(IllegalArgumentException.class, () -> verify(namedOriginal, namedLatest, ""));
            assertThrows(IllegalArgumentException.class, () -> verify(namedOriginal, namedLatest,
                    history().replace("원공고 제목", title).replace("정정 제목", title).replace("정정공고", "일반공고")));
        }
    }

    @Test
    void legacyExceptionCannotOverrideOtherDestinationsKindsIdsOrTitles() {
        for (String bad : new String[]{original().replace("currPanId = '" + OLD, "currPanId = '999"),
                original().replace("var panKdCd = '01'", "var panKdCd = '02'"),
                original().replace("value=\"" + OLD, "value=\"999"), original().replace("원공고 제목", "별도 모집")})
            assertThrows(IllegalArgumentException.class, () -> verify(bad, latest(), history()));
        assertThrows(IllegalArgumentException.class, () -> verify(original(), latest().replace("정정 제목", "다른 정정"), history()));
        assertThrows(IllegalArgumentException.class, () -> verify(original(), latest(), history().replace(OLD, "999")));
        assertThrows(IllegalArgumentException.class, () -> verify(original(), latest(), history() + history()));
        assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verifyFamilyHistory(
                url(OLD).replace("aisTpCd=08", "aisTpCd=09"), original(), url(NEXT).replace("aisTpCd=08", "aisTpCd=09"), latest(), history()));
    }

    @Test
    void emptyGenericOrAmbiguousModificationTextIsRejected() {
        for (String replacement : new String[]{"일반 모집 안내", "수정정보 : ", "수정정보 : " + "가".repeat(2001)})
            assertThrows(IllegalArgumentException.class, () -> verify(original(), latest().replace("수정정보 : 주택관리번호 변경\n수정된 번호 안내", replacement), history()));
        assertThrows(IllegalArgumentException.class, () -> verify(original(), latest() +
                "<div class='bbsV_cont'><dt>내용</dt><dd><pre>수정정보 : 다른 안내</pre></dd></div>", history()));
    }
}
