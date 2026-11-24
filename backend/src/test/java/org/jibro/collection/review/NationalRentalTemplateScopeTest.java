package org.jibro.collection.review;

import java.util.List;
import java.util.function.UnaryOperator;

import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.Content;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class NationalRentalTemplateScopeTest {
    record Fixture(Draft draft, Content original) {
    }

    private Source official() {
        try (var stream = getClass().getResourceAsStream("/review/iksan-waiver-20261104.json")) {
            return new ObjectMapper().readValue(stream, Fixture.class).draft().sources().stream()
                    .filter(source -> source.label().endsWith(".pdf")).findFirst().orElseThrow();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Source changed(UnaryOperator<String> change) {
        var source = official();
        String text = source.context().stream().map(Part::text).reduce("", (a, b) -> a + "\n" + b);
        String altered = change.apply(text);
        assertNotEquals(text, altered, "mutation must change the actual fixture");
        return new Source(source.label(), source.url(), source.sha256(), source.status(), source.message(), source.excerpts(),
                List.of(new Part("원문 대조", altered)));
    }

    @Test
    void actualReviewedTemplateSupportsTheBoundedDraft() {
        assertTrue(NationalRentalTemplateScope.matches(official()));
    }

    @Test
    void anotherOccurrenceOfFourYearsCannotHideAChangedApplicationRule() {
        var source = changed(text -> text.replaceFirst("적발된 후 4년", "적발된 후 5년"));
        assertTrue(source.context().get(0).text().contains("적발된 후 4년"));
        assertFalse(NationalRentalTemplateScope.matches(source));
    }

    @Test
    void missingOrNegatedMinorExceptionsDoNotReuseTheReviewedParaphrase() {
        assertFalse(NationalRentalTemplateScope.matches(changed(text -> text.replace("자녀가 있는 미성년 세대주", "자녀가 없는 미성년 세대주"))));
        assertFalse(NationalRentalTemplateScope.matches(changed(text -> text.replace("19세 미만", "20세 미만"))));
        assertFalse(NationalRentalTemplateScope.matches(changed(text -> text.replace("외국인 부모가 대리하여 신청", "외국인 부모는 대리하여 신청 불가"))));
    }

    @Test
    void changedNationalityAndSeparatedSpouseRulesStayOutsideTheTemplate() {
        assertFalse(NationalRentalTemplateScope.matches(changed(text -> text.replace("외국인은 신청 불가합니다.", "외국인은 신청 가능합니다."))));
        assertFalse(NationalRentalTemplateScope.matches(changed(text -> text.replace("‘분리배우자’) 포함", "‘분리배우자’) 제외"))));
        assertFalse(NationalRentalTemplateScope.matches(changed(text -> text.replace("분리된 신청자의 배우자", "분리되지 않은 신청자의 배우자"))));
    }

    @Test
    void passportAndProxyStatementsMustMatchInApplicationSectionNotContractAppendix() {
        var source = changed(text -> text.replaceFirst("2020.12.21.", "2021.12.21."));
        assertTrue(source.context().get(0).text().contains("2020.12.21."));
        assertFalse(NationalRentalTemplateScope.matches(source));
        assertFalse(NationalRentalTemplateScope.matches(changed(text -> text.replaceFirst("본인 도장\\(서명으로 대체 가능\\)", "본인 도장(서명으로 대체 불가)"))));
    }

    @Test
    void vehicleAggregationAndFirstComeRulesCannotBeBorrowedFromOtherSections() {
        assertFalse(NationalRentalTemplateScope.matches(changed(text -> text.replace("각각의 자동차가액 중 높은 차량가액", "각각의 자동차가액 합산 차량가액"))));
        assertFalse(NationalRentalTemplateScope.matches(changed(text -> text.replace("상관없이 신청접수 순번대로", "우선 적용하여 신청접수 순번대로"))));
    }
}
