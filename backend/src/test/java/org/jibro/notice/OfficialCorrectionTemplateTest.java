package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** Minimal structural fixtures from the official 2013/2016 detail template; no copied applicant data. */
class OfficialCorrectionTemplateTest {
    static final String ORIGINAL = "2015122300000890", TARGET = "2015122300000903";
    static final String LEGACY_ORIGINAL = "000000000000000000000000004175";
    static final String LEGACY_TARGET = "000000000000000000000000004188";

    static String page(String id, String current, String original, String kind) {
        return """
            <form id="wrtancViewForm"><input name="panId" value="%s"></form>
            <dl><dt>정정사유</dt><dd>접수 일정 안내 수정</dd></dl>
            <script>
            var panId = '%s';
            var currPanId = '%s';
            var panKdCd = '%s';
            var currPanKdCd = '02';
            function GoCrc(param){
                var ccrCnntSysDsCd = '03';
                var panKdCd = '02';
                var panId = '%s';
                var sOtxtPanId = '%s';
                if(isEmpty(param)){
                    if(sOtxtPanId == null || sOtxtPanId == ""){
                        sOtxtPanId = '%s';
                    }
                    $('#ccrCnntSysDsCd').val(ccrCnntSysDsCd);
                    $('#panKdCd').val(panKdCd);
                    $('#panId').val(panId);
                    $('#otxtPanId').val(sOtxtPanId);
                }else{
                    if(param == 'curr'){
                        panId = '';
                    }else if(param == 'otxt'){
                        panId = '';
                    }
                    $('#ccrCnntSysDsCd').val(ccrCnntSysDsCd);
                    $('#panKdCd').val(panKdCd);
                    $('#panId').val(panId);
                }
                var chkOs = getCookie("d");
                if("ios" ==chkOs) {
                    $("#wrtancViewForm").attr('method', 'get');
                }
                $("#wrtancViewForm").attr('action', "/lhapply/apply/wt/wrtanc/selectWrtancInfo.do").submit();
            }
            </script>
            """.formatted(id, id, current, kind, current, original, id);
    }

    private OfficialCorrectionLink.Proof verify(String old, String target) {
        return OfficialCorrectionLink.verify(OfficialCorrectionLinkTest.url(ORIGINAL), old,
            OfficialCorrectionLinkTest.url(TARGET), target);
    }

    @Test void acceptsObservedStaticFallbackWithoutExecutingTheTemplate() {
        var proof = verify(page(ORIGINAL, TARGET, "", "01"), page(TARGET, TARGET, ORIGINAL, "02"));
        assertEquals(ORIGINAL, proof.originalPanId());
        assertEquals(TARGET, proof.correctedPanId());
        assertEquals("접수 일정 안내 수정", proof.reason());
    }

    @Test void acceptsThirtyDigitHistoricalIdentityWithoutStrippingLeadingZeros() {
        var proof = OfficialCorrectionLink.verify(OfficialCorrectionLinkTest.url(LEGACY_ORIGINAL),
            page(LEGACY_ORIGINAL, LEGACY_TARGET, "", "01"), OfficialCorrectionLinkTest.url(LEGACY_TARGET),
            page(LEGACY_TARGET, LEGACY_TARGET, LEGACY_ORIGINAL, "02"));
        assertEquals(LEGACY_ORIGINAL, proof.originalPanId());
        assertEquals(LEGACY_TARGET, proof.correctedPanId());
        assertThrows(IllegalArgumentException.class,
            () -> OfficialCorrectionLink.identity(OfficialCorrectionLinkTest.url("0" + LEGACY_ORIGINAL)));
        assertThrows(IllegalArgumentException.class,
            () -> OfficialCorrectionLink.identity(OfficialCorrectionLinkTest.url("4175a")));
    }

    @Test void fixedFallbackMustReferToThisPageAndCannotIntroduceDynamicDestinations() {
        var old = page(ORIGINAL, TARGET, "", "01");
        var target = page(TARGET, TARGET, ORIGINAL, "02");
        for (String replacement : new String[]{"'999'", "calculateOriginal()", "'" + TARGET + "' + suffix"}) {
            assertThrows(IllegalArgumentException.class, () -> verify(old,
                target.replace("sOtxtPanId = '" + TARGET + "';", "sOtxtPanId = " + replacement + ";")), replacement);
        }
        for (String replacement : new String[]{"panId = '999';", "panId = calculateId();", "panId += '9';"}) {
            assertThrows(IllegalArgumentException.class, () -> verify(old, target.replace("panId = '';", replacement)), replacement);
        }
    }

    @Test void recognizedTemplateCannotBeFollowedByOverridesOrAnAlternateSubmission() {
        var old = page(ORIGINAL, TARGET, "", "01");
        var target = page(TARGET, TARGET, ORIGINAL, "02");
        for (String extra : new String[]{"panId='999';", "sOtxtPanId='999';", "panKdCd='03';",
                "$('#panId').val(calculateId());", "$('#ccrCnntSysDsCd').val('02');"}) {
            assertThrows(IllegalArgumentException.class, () -> verify(old, target.replace("var chkOs", extra + "var chkOs")), extra);
        }
        assertThrows(IllegalArgumentException.class, () -> verify(old,
            target.replace("/lhapply/apply/wt/wrtanc/selectWrtancInfo.do", "https://example.invalid/notice")));
        assertThrows(IllegalArgumentException.class, () -> verify(old,
            target.replace(".val(sOtxtPanId)", ".val(calculateOriginal())")));
        assertThrows(IllegalArgumentException.class, () -> verify(old,
            target.replace("isEmpty(param)", "true")));
    }

    @Test void sameTemplateStillRequiresBothDirectionsReasonAndMatchingOfficialCodes() {
        var old = page(ORIGINAL, TARGET, "", "01");
        var target = page(TARGET, TARGET, ORIGINAL, "02");
        // Mirrors the unbound original-ID shape observed on the Naepo target page.
        assertThrows(IllegalArgumentException.class, () -> verify(old, page(TARGET, TARGET, "", "02")));
        assertThrows(IllegalArgumentException.class, () -> verify(old, page(TARGET, TARGET, "999", "02")));
        assertThrows(IllegalArgumentException.class, () -> verify(old,
            target.replace("<dt>정정사유</dt><dd>접수 일정 안내 수정</dd>", "<pre>[정정사유] 접수 일정 안내 수정</pre>")));
        assertThrows(IllegalArgumentException.class, () -> verify(old, target.replace("ccrCnntSysDsCd = '03'", "ccrCnntSysDsCd = '02'")));
        assertThrows(IllegalArgumentException.class, () -> verify(old, target.replace("panKdCd = '02'", "panKdCd = '03'")));
        for (String mismatch : new String[]{"aisTpCd=10", "uppAisTpCd=05", "ccrCnntSysDsCd=02"}) {
            String key = mismatch.substring(0, mismatch.indexOf('='));
            String targetUrl = OfficialCorrectionLinkTest.url(TARGET).replaceAll(key + "=[0-9]+", mismatch);
            assertThrows(IllegalArgumentException.class, () -> OfficialCorrectionLink.verify(
                OfficialCorrectionLinkTest.url(ORIGINAL), old, targetUrl, target));
        }
    }
}
