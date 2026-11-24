package org.jibro.collection.review;

import java.time.LocalDate;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class SupplementaryRulesTest {
    private final ObjectMapper json = new ObjectMapper();

    Source primary() throws Exception {
        try (var in = getClass().getResourceAsStream("/review/suwon-main-variants.json")) {
            return json.treeToValue(json.readTree(in).get("fragments"), Source.class);
        }
    }

    Source[] forms() throws Exception {
        try (var in = getClass().getResourceAsStream("/review/suwon-numbered-forms.json")) {
            return json.readValue(in, Source[].class);
        }
    }

    @Test
    void fullFormBundlesBindTheirStageAndPreserveFooterDisagreements() throws Exception {
        var forms = forms();
        var first = SupplementaryRules.readForms(forms[0], primary(), LocalDate.parse("2026-11-18"));
        var second = SupplementaryRules.readForms(forms[1], primary(), LocalDate.parse("2026-11-18"));
        assertTrue(first.complete(), first.blockers().toString());
        assertTrue(second.complete(), second.blockers().toString());
        assertEquals(List.of(SupplementaryRules.Role.RENEWAL_REFERENCE, SupplementaryRules.Role.WAITLIST,
            SupplementaryRules.Role.CONSENT, SupplementaryRules.Role.POST_VERIFICATION), first.rules().stream().map(SupplementaryRules.FormRule::role).toList());
        assertEquals(List.of(2025), first.rules().get(2).signingYears());
        assertEquals(List.of("경기북부"), first.rules().get(2).recipientOffices());
        assertEquals(SupplementaryRules.Stage.RENEWAL, first.rules().get(0).claims().get(0).scope().stage());
        assertEquals(List.of("STUDENT"), second.rules().get(1).claims().get(0).scope().applicantTypes());
        assertTrue(first.rules().get(1).claims().stream().anyMatch(c -> c.scope().stage() == SupplementaryRules.Stage.SELECTION));
        assertTrue(first.rules().get(1).claims().stream().anyMatch(c -> c.scope().stage() == SupplementaryRules.Stage.ENTRY));
        assertEquals(Set.of(SupplementaryRules.Stage.APPLICATION, SupplementaryRules.Stage.CONTRACT), second.rules().get(2).claims().stream().map(c -> c.scope().stage()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void anUnrecognizedAdditionalPromiseCannotBecomeARecognizedBundle() throws Exception {
        var form = forms()[0];
        var changed = new Source(form.label(), form.url(), form.sha256(), form.status(), form.message(), form.excerpts(),
            form.context().stream().map(p -> new Part(p.location(), p.text().replace("3. (무주택)", "4. 모든 신청자는 추가 보증금을 납부해야 합니다.\n3. (무주택)"))).toList());
        assertNotEquals(form.context(), changed.context());
        var result = SupplementaryRules.readForms(changed, primary(), LocalDate.parse("2026-11-18"));
        assertFalse(result.complete());
        assertTrue(result.blockers().contains("SUPPLEMENTARY_FORM_BUNDLE_UNPARSED:1"));
    }

    @Test
    void unchangedWordsDoNotHideReassignedFacilityTargets() throws Exception {
        var form = forms()[1];
        var parts = form.context().stream().map(p -> new Part(p.location(), p.text()
            .replace("table=12 row=22 col=8", "table=12 row=TEMP col=8")
            .replace("table=12 row=23 col=8", "table=12 row=22 col=8")
            .replace("table=12 row=TEMP col=8", "table=12 row=23 col=8"))).toList();
        assertNotEquals(form.context(), parts);
        var changed = new Source(form.label(), form.url(), form.sha256(), form.status(), form.message(), form.excerpts(), parts);
        var result = SupplementaryRules.readForms(changed, primary(), LocalDate.parse("2026-11-18"));
        assertFalse(result.complete());
        assertTrue(result.blockers().contains("SUPPLEMENTARY_FORM_TABLE_SCOPE_UNVERIFIED:8"));
    }

    @Test
    void eachRecruitmentSlotMustIdentifyTheSamePrimaryHeader() throws Exception {
        var form = forms()[1];
        var parts = form.context().stream().map(p -> new Part(p.location(), p.text().replace("수원당수 1·2단지 행복주택", "다른모집 1·2단지 행복주택"))).toList();
        assertNotEquals(form.context(), parts);
        var changed = new Source(form.label(), form.url(), form.sha256(), form.status(), form.message(), form.excerpts(), parts);
        var result = SupplementaryRules.readForms(changed, primary(), LocalDate.parse("2026-11-18"));
        assertFalse(result.complete());
        assertTrue(result.blockers().contains("SUPPLEMENTARY_FORM_RECRUITMENT_MISMATCH:5"));
    }

    @Test
    void targetTextCannotMoveIntoAnAdjacentCheckboxWhileCoordinatesStayTheSame() throws Exception {
        var form = forms()[1];
        var parts = form.context().stream().map(p -> new Part(p.location(), p.text()
            .replace("table=12 row=22 col=8 rowspan=1 colspan=2⟧\n④,⑤", "table=12 row=22 col=8 rowspan=1 colspan=2⟧\n")
            .replace("table=12 row=22 col=10 rowspan=1 colspan=1 left=DRAWN right=NONE⟧\n□", "table=12 row=22 col=10 rowspan=1 colspan=1 left=DRAWN right=NONE⟧\n④,⑤\n□"))).toList();
        assertNotEquals(form.context(), parts);
        var changed = new Source(form.label(), form.url(), form.sha256(), form.status(), form.message(), form.excerpts(), parts);
        assertEquals(VerifiedPreparationForms.numberedBlocks(form).get(3).text().replaceAll("\\s+", ""),
            VerifiedPreparationForms.numberedBlocks(changed).get(3).text().replaceAll("\\s+", ""));
        var result = SupplementaryRules.readForms(changed, primary(), LocalDate.parse("2026-11-18"));
        assertFalse(result.complete());
        assertTrue(result.blockers().contains("SUPPLEMENTARY_FORM_BUNDLE_UNPARSED:8"));
    }
}
