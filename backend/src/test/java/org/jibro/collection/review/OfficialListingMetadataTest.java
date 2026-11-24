package org.jibro.collection.review;

import java.util.*;
import java.util.function.UnaryOperator;

import org.jibro.collection.extraction.DocumentTextParser;
import org.jibro.collection.extraction.ExcerptClassifier;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.notice.HousingModels.Evidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class OfficialListingMetadataTest {
    private record Pair(Source listing, Source booklet) {
    }

    private Pair actual() throws Exception {
        var json = new ObjectMapper();
        var fixture = json.readTree(getClass().getResourceAsStream("/review/bukgu-source.json"));
        var draft = json.treeToValue(fixture.get("draft"), Draft.class);
        var listing = draft.sources().stream().filter(s -> s.label().equals("공식 상세 본문")).findFirst().orElseThrow();
        var booklet = draft.sources().stream().filter(s -> s.label().endsWith(".hwpx")).findFirst().orElseThrow();
        try (var bytes = getClass().getResourceAsStream("/extraction/lh-bukgu-69296396.hwpx")) {
            var parts = new DocumentTextParser().parse(bytes.readAllBytes(), "application/zip");
            return new Pair(listing, new Source(booklet.label(), booklet.url(), booklet.sha256(), "EXTRACTED", "공식 원형", ExcerptClassifier.extract(parts, booklet.url()), parts));
        }
    }

    @Test
    void RepresentativeResultNeedsTheExactFieldAndVerifiedTargetSpecificScheduleWarning() throws Exception {
        var pair = actual();
        var evidence = new Evidence("공식 상세 본문 · 항목 6", pair.listing().url(), "2026-11-23");
        assertTrue(OfficialListingMetadata.representativeAnnouncement(List.of(pair.listing(), pair.booklet()), evidence));
        assertFalse(OfficialListingMetadata.representativeAnnouncement(List.of(pair.listing(), pair.booklet()),
                new Evidence("공식 상세 본문 · 항목 9", pair.listing().url(), "2026-11-23")));
        var noWarning = changed(pair.listing(), 11, text -> "안내사항");
        assertFalse(OfficialListingMetadata.representativeAnnouncement(List.of(noWarning, pair.booklet()), evidence));
        var differentRanks = changed(pair.listing(), 9, text -> text.replace("70%", "80%"));
        assertFalse(OfficialListingMetadata.representativeAnnouncement(List.of(differentRanks, pair.booklet()), evidence));
    }

    @Test
    void actualListingRanksNeedEveryCompleteCriterionUnderTheSameRankAndPriority() throws Exception {
        var pair = actual();
        assertTrue(OfficialListingMetadata.repeatedGuidance(pair.listing(), List.of(pair.listing(), pair.booklet())));
        List<Source> changes = List.of(
                changed(pair.listing(), 9, text -> text.replace("구분 : 1순위", "구분 : 2순위")),
                changed(pair.listing(), 9, text -> text.replaceFirst("우선", "일반")),
                changed(pair.listing(), 9, text -> text.replaceAll("(?m)^.*한부모가족.*\\R?", "")),
                changed(pair.listing(), 9, text -> text.replace("70%", "80%")),
                changed(pair.listing(), 8, text -> text.replace("2026.11.11.", "2026.11.12.")),
                changed(pair.listing(), 9, text -> text.substring(0, text.indexOf("70%"))),
                changed(pair.listing(), 8, text -> text + " 단, 주택 소유자도 가능합니다."),
                changed(pair.listing(), 10, text -> text.replace("해당 세대", "신청자 본인")),
                changed(pair.listing(), 9, text -> text.replace("(지적장애인·정신장애인 및 장애의 정도가 심한 뇌병변장애인의 경우 배우자 포함)", "")));
        for (var changed : changes)
            assertFalse(OfficialListingMetadata.repeatedGuidance(changed, List.of(changed, pair.booklet())), changed.context().toString());
    }

    @Test
    void EmptyFormControlsAndHeaderCannotHideNewDatesOrIndependentRules() throws Exception {
        var pair = actual();
        for (var changed : List.of(
                changed(pair.listing(), 5, text -> text + " 마감 이후에도 접수 가능"),
                changed(pair.listing(), 11, text -> text.replace("상이", "동일")),
                changed(pair.listing(), 13, text -> text.replace("전화번호 : :", "접수기간 :")),
                changed(pair.listing(), 14, text -> text + "\n예외로 80%까지 허용합니다.")))
            assertFalse(OfficialListingMetadata.repeatedGuidance(changed, List.of(changed, pair.booklet())));
        var unrelated = new Source(pair.listing().label(), pair.listing().url(), pair.listing().sha256(), "EXTRACTED", "",
                List.of(new Excerpt("CONDITION", "한부모가족 제외", "공식 상세 본문 · 항목 9", pair.listing().url())), pair.listing().context());
        assertFalse(OfficialListingMetadata.repeatedGuidance(unrelated, List.of(unrelated, pair.booklet())));
    }

    @Test
    void ANoticeBoxInsideARestrictedParentIsNotGlobalGuidance() throws Exception {
        var pair = actual();
        var parts = new ArrayList<>(pair.booklet().context());
        boolean wrapped = false;
        for (int index = 0; index < parts.size(); index++) {
            var part = parts.get(index);
            var reading = org.jibro.collection.review.local.DocumentContextReader.read(pair.booklet(), part);
            var target = reading.tables().stream().filter(table -> table.rows() == 1 && table.columns() == 1
                    && reading.text(part.text(), table.start(), table.end()).contains("신청순위별 접수")).findFirst();
            if (target.isEmpty()) continue;
            var table = target.get();
            String nested = "⟦HWPX:TABLE id=999 rows=1 cols=1⟧\n⟦HWPX:CELL table=999 row=1 col=1 rowspan=1 colspan=1⟧\n"
                    + "신혼부부 신청자에게만 적용\n" + part.text().substring(table.start(), table.end())
                    + "\n⟦HWPX:/CELL⟧\n⟦HWPX:/TABLE⟧";
            parts.set(index, new Part(part.location(), part.text().substring(0, table.start()) + nested + part.text().substring(table.end())));
            wrapped = true;
            break;
        }
        assertTrue(wrapped);
        var restricted = new Source(pair.booklet().label(), pair.booklet().url(), pair.booklet().sha256(), "EXTRACTED", "",
                List.of(), parts);
        assertFalse(OfficialListingMetadata.repeatedGuidance(pair.listing(), List.of(pair.listing(), restricted)));
    }

    private static Source changed(Source original, int part, UnaryOperator<String> change) {
        var parts = new ArrayList<>(original.context());
        var previous = parts.get(part - 1);
        String text = change.apply(previous.text());
        assertNotEquals(previous.text(), text);
        parts.set(part - 1, new Part(previous.location(), text));
        return new Source(original.label(), original.url(), original.sha256(), original.status(), original.message(),
                ExcerptClassifier.extract(parts, original.url()), parts);
    }
}
