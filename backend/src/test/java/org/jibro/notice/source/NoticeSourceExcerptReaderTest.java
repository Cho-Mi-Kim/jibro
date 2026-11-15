package org.jibro.notice.source;

import static org.jibro.notice.source.NoticeSourceInformationModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OcrProvenance;
import org.jibro.notice.source.NoticeSourceInformationRepository.Snapshot;
import org.junit.jupiter.api.Test;

class NoticeSourceExcerptReaderTest {
    private static final String URL = "https://apply.lh.or.kr/notice.pdf";
    private static final Set<String> HOSTS = Set.of("apply.lh.or.kr", "www.lh.or.kr");

    private Source source(String label, String url, String status, String hash, List<Part> parts) {
        return new Source(label, url, hash, status, "internal source status", List.of(), parts);
    }

    private Snapshot snapshot(List<Source> sources) {
        return new Snapshot(UUID.randomUUID(), 2, UUID.randomUUID(), "f".repeat(64), Instant.now(), sources);
    }

    private Information read(List<Source> sources) { return NoticeSourceExcerptReader.read(snapshot(sources), HOSTS); }

    @Test void keepsOriginalWholePartIncludingExceptionsAndDoesNotInventAnAnswerableRule() {
        String original = "  신청 자격: 무주택 세대구성원\n다만, 예외 적용 대상은 다음 표를 함께 확인하세요.\n소득 요건을 배제하는 별도 유형은 이 기준을 적용하지 않습니다.\n";
        var result = read(List.of(source("공고.pdf", URL, "EXTRACTED", "a".repeat(64), List.of(new Part("공고문 4쪽", original)))));
        assertEquals(State.AVAILABLE, result.state());
        assertEquals(List.of(Area.DOCUMENT, Area.SCHEDULE), result.pendingAreas());
        assertEquals(1, result.sections().size());
        assertEquals(original, result.sections().get(0).text());
        assertEquals("공고문 4쪽", result.sections().get(0).title());
        assertEquals("공고.pdf · 공고문 4쪽", result.sections().get(0).evidence().label());
    }

    @Test void categoryHintsInspectTheFullPartEvenWhenExtractionIndexWouldStopAtNinetyLines() {
        String original = "접수 안내 문장\n".repeat(100) + "신청 자격의 예외는 원문 확인\n제출 서류는 해당자에 한함";
        var result = read(List.of(source("공고.pdf", URL, "EXTRACTED", "a".repeat(64), List.of(new Part("공고문 1쪽", original)))));
        assertEquals(Set.of(Area.CONDITION, Area.DOCUMENT, Area.SCHEDULE), Set.copyOf(result.sections().stream().map(Section::area).toList()));
        assertTrue(result.sections().stream().allMatch(section -> section.text().equals(original)));
        assertTrue(result.pendingAreas().isEmpty());
    }

    @Test void failedEmptyUnhashedAndUntrustedSourcesAreOmittedWhileSuccessfulOriginalsRemain() {
        var part = new Part("공고문 1쪽", "신청 자격과 제출 서류, 접수 기간 안내");
        var result = read(List.of(source("정상.pdf", URL, "EXTRACTED", "a".repeat(64), List.of(part)),
            source("실패.pdf", URL, "FAILED", "a".repeat(64), List.of(part)),
            source("빈자료.pdf", URL, "EMPTY", "a".repeat(64), List.of(part)),
            source("해시없음.pdf", URL, "EXTRACTED", null, List.of(part)),
            source("외부.pdf", "https://example.org/notice.pdf", "EXTRACTED", "a".repeat(64), List.of(part)),
            source("비허용주소.pdf", "http://apply.lh.or.kr/notice.pdf", "EXTRACTED", "a".repeat(64), List.of(part))));
        assertEquals(3, result.sections().size());
        assertTrue(result.sections().stream().allMatch(section -> section.evidence().label().startsWith("정상.pdf")));
    }

    @Test void oversizedAndAlreadyTruncatedPartsAreOmittedWithoutSnippingTheirExceptions() {
        var result = read(List.of(source("공고.hwpx", URL, "EXTRACTED", "a".repeat(64), List.of(
            new Part("HWPX Contents/section0.xml (쪽 번호 확인 필요)", "신청 자격 " + "가".repeat(NoticeSourceExcerptReader.MAX_PART_CHARACTERS)),
            new Part("공고문 2쪽", "신청 자격 … [발췌 길이 제한]")))));
        assertEquals(State.PENDING, result.state());
        assertTrue(result.sections().isEmpty());
        assertEquals(GUIDANCE_AREAS, result.pendingAreas());
    }

    @Test void preservesTheOriginalUnknownPageLocationOfHwpx() {
        String location = "HWPX Contents/section0.xml (쪽 번호 확인 필요)";
        var result = read(List.of(source("공고.hwpx", URL, "EXTRACTED", "a".repeat(64), List.of(new Part(location, "제출 서류는 해당자에게만 적용됩니다.")))));
        assertEquals(location, result.sections().get(0).title());
        assertEquals("공고.hwpx · " + location, result.sections().get(0).evidence().label());
    }

    @Test void drawingTextIsReferenceOnlyEvenWhenItMentionsEligibilityOrSubmission() {
        var parts = new ArrayList<Part>();
        for (int i = 1; i <= 5; i++) parts.add(new Part(OcrProvenance.MARKER + " HWP BinData/image" + i,
            "51A · 301동 · 제출서류 · 자격 기준은 본 공고 확인 " + i));
        var result = read(List.of(source("별가람 평면도.hwp", URL, "EXTRACTED", "a".repeat(64), parts)));
        assertEquals(5, result.sections().size());
        assertTrue(result.sections().stream().allMatch(section -> section.area() == Area.REFERENCE));
        assertEquals(GUIDANCE_AREAS, result.pendingAreas());
        assertEquals(URL, result.sections().get(0).evidence().url());
    }

    @Test void preservesOcrProvenanceAndLiteralReadingErrorsWithoutCorrectingThePublicSourceText() {
        String location = "공고문 3쪽 " + OcrProvenance.MARKER;
        String original = "  신청 자격\r\n소득 3,O00,0OO원 이하, 만 l9세 이상\r\n"
            + "다만, 예외는 원문 표에서 확인하세요.\r\n제출 서류: 주민등록표등본\r\n접수 기간: 2O26.11.O9\r\n";
        var source = source("스캔공고.pdf", URL, "EXTRACTED", "a".repeat(64), List.of(new Part(location, original)));
        var snapshot = new Snapshot(UUID.randomUUID(), 2, UUID.randomUUID(), "f".repeat(64),
            Instant.parse("2026-01-01T16:00:00Z"), List.of(source));
        var result = NoticeSourceExcerptReader.read(snapshot, HOSTS);

        assertEquals(State.AVAILABLE, result.state(), "Source text availability is not guidance approval");
        assertTrue(result.pendingAreas().isEmpty());
        assertEquals(Set.of(Area.CONDITION, Area.DOCUMENT, Area.SCHEDULE),
            Set.copyOf(result.sections().stream().map(Section::area).toList()));
        for (var section : result.sections()) {
            assertEquals(original, section.text());
            assertEquals(location, section.title());
            assertEquals("스캔공고.pdf · " + location, section.evidence().label());
            assertEquals(URL, section.evidence().url());
            assertEquals("2026-01-02", section.evidence().checkedAt());
        }
    }

    @Test void incompleteOcrSourceShowsOnlyReadablePartsAndNeverFillsTheUnreadPage() {
        var nativePart = new Part("공고문 1쪽", "신청 자격: 무주택 세대구성원, 예외는 공식 원문 확인");
        var readOcr = new Part("공고문 2쪽 " + OcrProvenance.MARKER, "제출 서류: 주민등록표등본\n해당자만 제출");
        var unread = new Part("공고문 3쪽 " + OcrProvenance.MARKER, "");
        var result = read(List.of(source("스캔공고.pdf", URL, "EXTRACTED", "a".repeat(64),
            List.of(nativePart, readOcr, unread))));

        assertEquals(State.AVAILABLE, result.state());
        assertEquals(List.of(Area.SCHEDULE), result.pendingAreas());
        assertEquals(2, result.sections().size());
        assertEquals(List.of(nativePart.text(), readOcr.text()), result.sections().stream().map(Section::text).toList());
        assertEquals(List.of(nativePart.location(), readOcr.location()), result.sections().stream().map(Section::title).toList());
        assertTrue(result.sections().stream().noneMatch(s -> s.title().equals(unread.location())));
        assertEquals("스캔공고.pdf · " + readOcr.location(), result.sections().get(1).evidence().label());
        assertTrue(result.sections().stream().allMatch(s -> s.evidence().url().equals(URL)));
    }

    @Test void selectionsAreDeterministicBoundedAndIdenticalOriginalPartsAreNotRepeated() {
        var parts = new ArrayList<Part>();
        for (int i = 1; i <= 10; i++) parts.add(new Part("공고문 " + i + "쪽", "신청 자격과 제출 서류, 접수 기간 " + i));
        parts.add(parts.get(0));
        var snapshot = snapshot(List.of(source("공고.pdf", URL, "EXTRACTED", "a".repeat(64), parts)));
        var first = NoticeSourceExcerptReader.read(snapshot, HOSTS);
        assertEquals(first, NoticeSourceExcerptReader.read(snapshot, HOSTS));
        assertEquals(3 * NoticeSourceExcerptReader.MAX_SECTIONS_PER_AREA, first.sections().size());
        assertEquals(first.sections().size(), first.sections().stream().map(Section::id).distinct().count());
        assertTrue(first.sections().stream().noneMatch(section -> section.title().contains("4쪽")));
    }

    @Test void realSectionHeadingsAfterCoverPagesAreChosenBeforePassingKeywordMentions() {
        var parts = new ArrayList<Part>();
        for (int i = 1; i <= 4; i++) parts.add(new Part("공고문 " + i + "쪽", "모집 요약: 무주택 여부, 주민등록 증명서와 접수 안내를 참고하세요."));
        String condition = "일반 안내 문장\n".repeat(100) + "4. 신청 자격\n무주택 세대구성원\n다만, 예외는 다음 페이지를 함께 확인하세요.";
        String documents = "8. 제출서류\n주민등록표등본\n해당자만 제출하며 공통 서류와 구분합니다.";
        String schedule = "3. 공급일정\n신청 기간: 2026.11.25 ~ 2026.11.26";
        parts.add(new Part("공고문 5쪽", condition));
        parts.add(new Part("공고문 9쪽", documents));
        parts.add(new Part("공고문 10쪽", schedule));
        var result = read(List.of(source("공고.pdf", URL, "EXTRACTED", "a".repeat(64), parts)));
        assertEquals(condition, result.sections().stream().filter(section -> section.area() == Area.CONDITION).findFirst().orElseThrow().text());
        assertEquals(documents, result.sections().stream().filter(section -> section.area() == Area.DOCUMENT).findFirst().orElseThrow().text());
        assertEquals(schedule, result.sections().stream().filter(section -> section.area() == Area.SCHEDULE).findFirst().orElseThrow().text());
        assertEquals(3 * NoticeSourceExcerptReader.MAX_SECTIONS_PER_AREA, result.sections().size());
    }

    @Test void standaloneNumberedEligibilitySectionPrecedesVerificationAndTableMentions() {
        var parts = List.of(new Part("공고문 2쪽", "▪ 입주자격 검증\r\n원문 심사 절차 안내"),
            new Part("공고문 4쪽", " 4. 신청자격\r\n무주택 기준과 예외는 원문을 함께 확인하세요."),
            new Part("공고문 23쪽", "신청자격  • 신청 이후 부적격 사유가 발견되면 소명이 필요합니다."));
        var result = read(List.of(source("공고.pdf", URL, "EXTRACTED", "a".repeat(64), parts)));
        assertEquals(List.of("공고문 4쪽", "공고문 2쪽", "공고문 23쪽"), result.sections().stream().map(Section::title).toList());
        assertEquals(parts.get(1).text(), result.sections().get(0).text());
    }
}
