package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OfficialCorrectionLinkTest {
    static final String OLD = "2015122300021024", TARGET = "2015122300021026";

    static String url(String id) {
        return "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=" + id
                + "&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07&mi=1026";
    }

    static String html(String id, String current, String original) {
        return """
                <html><form id="wrtancViewForm"><input name="panId" value="%s"></form>
                <dl><dt>정정사유</dt><dd>현장신청 장소를 210동 회의실로 변경</dd></dl>
                <script>
                var panId = '%s';
                var currPanId = '%s';
                var panKdCd = '%s';
                var currPanKdCd = '02';
                function GoCrc(param) {
                    var ccrCnntSysDsCd = '03';
                    var panKdCd = '02';
                    var panId = '%s';
                    var sOtxtPanId = '%s';
                }
                </script></html>
                """.formatted(id, id, current, original.isEmpty() ? "01" : "02", current, original);
    }

    @Test
    void provesBothDirectionsWithoutTitleMatchingAndBindsHtmlDigests() {
        var proof = verify(html(OLD, TARGET, ""), html(TARGET, TARGET, OLD));
        assertEquals(OLD, proof.originalPanId());
        assertEquals(TARGET, proof.correctedPanId());
        assertEquals("현장신청 장소를 210동 회의실로 변경", proof.reason());
        assertTrue(proof.originalHtmlSha256().matches("[a-f0-9]{64}"));
        assertNotEquals(proof.originalHtmlSha256(), proof.correctedHtmlSha256());
    }

    static String branchHtml(String id, String original) {
        return html(id, TARGET, original).replace(
                "var panId = '" + TARGET + "';\n    var sOtxtPanId = '" + original + "';",
                "var panId = '';\n    if(param == 'curr'){ panId = '" + TARGET
                        + "'; }else if(param == 'otxt'){ panId = '" + original + "'; }");
    }

    @Test
    void readsExplicitCurrentAndOriginalBranchesWithoutExecutingJavascript() {
        var old = branchHtml(OLD, "");
        var corrected = branchHtml(TARGET, OLD);
        assertTrue(corrected.contains("if(param == 'curr')"));
        assertEquals(TARGET, verify(old, corrected).correctedPanId());
        assertThrows(IllegalArgumentException.class, () -> verify(old,
                corrected.replace("panId = '" + OLD + "'; }", "panId = '999'; }")));
        assertThrows(IllegalArgumentException.class, () -> verify(old,
                corrected.replace("panId = '" + OLD + "'; }", "panId = calculateOriginal(); }")));
    }

    @Test
    void multipleNavigationSchemasAreAmbiguous() {
        var old = branchHtml(OLD, "");
        var extra = "<script>\nfunction GoCrc(param) { var ccrCnntSysDsCd='03'; var panKdCd='02'; var panId='" + TARGET + "'; var sOtxtPanId=''; }</script>";
        assertThrows(IllegalArgumentException.class, () -> verify(old + extra, branchHtml(TARGET, OLD)));
    }

    @Test
    void laterAssignmentsCannotOverrideTheReviewedLiteralDestination() {
        for (boolean branch : new boolean[]{false, true}) {
            var old = branch ? branchHtml(OLD, "") : html(OLD, TARGET, "");
            var target = branch ? branchHtml(TARGET, OLD) : html(TARGET, TARGET, OLD);
            for (String mutation : new String[]{"panId = '999';", "if(param == 'curr'){ panId = '999'; }",
                    "panId += '9';", "++panId;", "sOtxtPanId = '999';", "panKdCd = '03';",
                    "panId /* changed template */ = '999';",
                    "var before = '/*'; panId = '999'; var after = '*/';"}) {
                assertThrows(IllegalArgumentException.class, () -> verify(old, appendBody(target, mutation)), mutation);
            }
        }
    }

    @Test
    void commentsReadOnlyComparisonsAndFormSubmissionDoNotLookLikeAssignments() {
        var target = appendBody(branchHtml(TARGET, OLD), """
                // panId = '999'; function GoCrc(other) {}
                /* panId += '9'; */
                var note = 'a string containing } and panId = 999';
                $('#ccrCnntSysDsCd').val(ccrCnntSysDsCd);
                $('#panKdCd').val(panKdCd);
                $('#panId').val(panId);
                if (panId == '%s') { $('#wrtancViewForm').attr('method', 'get'); }
                $('#wrtancViewForm').attr('action', '/lhapply/apply/wt/wrtanc/selectWrtancInfo.do').submit();
                """.formatted(TARGET));
        assertEquals(TARGET, verify(branchHtml(OLD, ""), target).correctedPanId());
    }

    @Test
    void unsupportedSecondFunctionOrReplacementIsNotIgnored() {
        var target = branchHtml(TARGET, OLD);
        for (String extra : new String[]{
                "function GoCrc(other) { return other; }", "function GoCrc(param) { panId = calculateId(); }",
                "GoCrc = function(param) { return '999'; };", "window.GoCrc = (param) => '999';"}) {
            assertThrows(IllegalArgumentException.class, () -> verify(branchHtml(OLD, ""),
                    target + "<script>" + extra + "</script>"));
        }
    }

    @Test
    void longUnrelatedInlineStringsCannotExhaustTheRegexStack() {
        String extra = "<script>var displayText='" + "x".repeat(100_000) + "';</script>";
        assertEquals(TARGET, verify(branchHtml(OLD, "") + extra, branchHtml(TARGET, OLD) + extra).correctedPanId());
    }

    private static String appendBody(String html, String statements) {
        String marker = "\n}\n</script>";
        assertTrue(html.contains(marker));
        return html.replace(marker, "\n" + statements + marker);
    }

    @Test
    void matchingTitlesAndOneDirectionDoNotProveCorrection() {
        assertThrows(IllegalArgumentException.class, () -> verify(
                "<h3>같은 공고 [정정]</h3>", html(TARGET, TARGET, OLD)));
        assertThrows(IllegalArgumentException.class, () -> verify(html(OLD, TARGET, ""), html(TARGET, TARGET, "123")));
    }

    @Test
    void newerCorrectionOrCancellationInvalidatesTarget() {
        assertThrows(IllegalArgumentException.class, () -> verify(html(OLD, TARGET, ""), html(TARGET, "999", OLD)));
        assertThrows(IllegalArgumentException.class, () -> verify(html(OLD, TARGET, "").replace("currPanKdCd = '02'", "currPanKdCd = '03'"), html(TARGET, TARGET, OLD)));
    }

    @Test
    void ambiguousScriptsAndMismatchedHiddenIdentityAreRejected() {
        var old = html(OLD, TARGET, "");
        assertThrows(IllegalArgumentException.class, () -> verify(old + old, html(TARGET, TARGET, OLD)));
        assertThrows(IllegalArgumentException.class, () -> verify(old.replace("value=\"" + OLD, "value=\"999"), html(TARGET, TARGET, OLD)));
        assertThrows(IllegalArgumentException.class, () -> verify(old, html(TARGET, TARGET, OLD).replace("<dt>정정사유</dt>", "<dt>추정 사유</dt>")));
    }

    @Test
    void unrelatedPopupFormIdsDoNotReplaceTheMainNoticeIdentity() {
        var old = html(OLD, TARGET, "") + "<form id='openHtyInfoPopupForm'><input name='panId' value=''></form>";
        assertEquals(TARGET, verify(old, html(TARGET, TARGET, OLD)).correctedPanId());
        assertThrows(IllegalArgumentException.class, () -> verify(old.replace("id=\"wrtancViewForm\"", "id=\"other\""), html(TARGET, TARGET, OLD)));
    }

    @Test
    void urlsMustIdentifyDistinctNoticesInTheSameOfficialSystem() {
        var old = html(OLD, TARGET, "");
        var target = html(TARGET, TARGET, OLD);
        for (String invalid : new String[]{url(OLD).replace("https:", "http:"), url(OLD).replace("apply.lh.or.kr", "example.com"),
                url(OLD) + "&panId=999", url(OLD).replace("selectWrtancInfo.do", "other.do"), url(OLD) + "#other"}) {
            assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verify(invalid, old, url(TARGET), target));
        }
        assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verify(url(OLD), old, url(TARGET).replace("aisTpCd=07", "aisTpCd=10"), target));
        assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verify(url(OLD), old, url(OLD), target));
    }

    private OfficialCorrectionLink.Proof verify(String old, String target) {
        return OfficialCorrectionLink.verify(url(OLD), old, url(TARGET), target);
    }
}
