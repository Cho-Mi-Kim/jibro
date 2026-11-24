package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OfficialCorrectionFamilyTest {
    static final String A = AutomaticCorrectionIntegrationTest.A;
    static final String B = AutomaticCorrectionIntegrationTest.B;
    static final String C = AutomaticCorrectionIntegrationTest.C;

    @Test
    void unrelatedFooterClosureCannotSuppressAnOpenOfficialDetail() {
        String page = AutomaticCorrectionIntegrationTest.page(C, C)
                + "<div class='bbs_ViewA'><ul class='bbsV_data'><li><strong>공고상태</strong><span>접수중</span></li></ul></div>"
                + "<footer><span class='stt end'>접수마감</span></footer>";
        assertFalse(OfficialCorrectionLink.explicitlyClosed(AutomaticCorrectionIntegrationTest.url(C), page));
        assertTrue(OfficialCorrectionLink.explicitlyClosed(AutomaticCorrectionIntegrationTest.url(C), page.replace("접수중", "접수마감")));
    }

    @Test
    void threeVersionHistoryProvesIntermediateAndOriginalAgainstTheLatest() {
        String latest = AutomaticCorrectionIntegrationTest.url(C);
        String history = AutomaticCorrectionIntegrationTest.history(C);
        assertEquals(3, OfficialCorrectionHistory.read(latest, history).size());
        for (String old : new String[]{A, B}) {
            var proof = OfficialCorrectionLink.verifyFamilyHistory(AutomaticCorrectionIntegrationTest.url(old),
                    AutomaticCorrectionIntegrationTest.page(old, C), latest,
                    AutomaticCorrectionIntegrationTest.page(C, C), history);
            assertEquals(old, proof.originalPanId());
            assertEquals(C, proof.correctedPanId());
            assertNotNull(proof.historyHtmlSha256());
        }
    }

    @Test
    void historyRejectsAmbiguityReversedDatesForeignSystemsAndDuplicateIdentities() {
        String history = AutomaticCorrectionIntegrationTest.history(C);
        for (String bad : new String[]{history.replace("공고이력조회", "일반 게시판"),
                history + history, history.replace(B, C), history.replace("','03')", "','02')"),
                history.replace("2026-11-09", "2026-11-01"), history.replace("일반공고", "취소공고"),
                history.replace("javascript:goUrl('" + A + "','03')", "https://example.invalid/")}) {
            assertThrows(IllegalArgumentException.class,
                    () -> OfficialCorrectionHistory.read(AutomaticCorrectionIntegrationTest.url(C), bad));
        }
    }

    @Test
    void historyCannotOverrideAMismatchedForwardLinkOrPageTitle() {
        var old = AutomaticCorrectionIntegrationTest.page(A, C);
        var latest = AutomaticCorrectionIntegrationTest.page(C, C);
        String history = AutomaticCorrectionIntegrationTest.history(C);
        assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verifyFamilyHistory(
                AutomaticCorrectionIntegrationTest.url(A), old.replace("currPanId = '" + C, "currPanId = '" + B),
                AutomaticCorrectionIntegrationTest.url(C), latest, history));
        assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verifyFamilyHistory(
                AutomaticCorrectionIntegrationTest.url(A), old, AutomaticCorrectionIntegrationTest.url(C),
                latest.replace("<h3>", "<h3>다른 공고 "), history));
    }

    @Test
    void candidateReplacementPreservesCodesAndLeadingZeroIdentity() {
        String url = OfficialCorrectionHistoryTest.url(OfficialCorrectionHistoryTest.OLD);
        assertEquals(OfficialCorrectionHistoryTest.url(OfficialCorrectionHistoryTest.NEXT),
                OfficialCorrectionLink.withPanId(url, OfficialCorrectionHistoryTest.NEXT));
        assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.withPanId(url, "999&panId=1"));
        assertEquals(OfficialCorrectionLink.identity(AutomaticCorrectionIntegrationTest.url(C)),
                OfficialCorrectionLink.identity(OfficialCorrectionLink.navigation(AutomaticCorrectionIntegrationTest.url(A),
                        AutomaticCorrectionIntegrationTest.page(A, C)).orElseThrow().latestUrl()));
    }
}
