package org.jibro.collection.review.followup;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.junit.jupiter.api.Test;

import static org.jibro.collection.review.followup.OfficialFollowupModels.*;

class OfficialFollowupRelationshipTest {
    static final Target TARGET = OfficialFollowupDiscoveryTest.TARGET;

    static List<Part> parts(String... text) {
        var parts = new ArrayList<Part>();
        for (int i = 0; i < text.length; i++)
            parts.add(new Part(i == 0 ? "공식 후속 공지 제목" : "공식 후속 공지 본문 · 항목 " + i, text[i]));
        return parts;
    }

    @Test
    void actualOriginalDateMayBeFourDigitsOrExplicitlyQuotedTwoDigits() {
        for (String title : List.of(
                "[당첨자 발표연기] 화성시병점복합타운A-1블럭 행복주택예비입주자 (‘22.08.05.공고)",
                "화성 병점복합타운A1블럭 행복주택 예비입주자 당첨자 발표(`22.08.05.모집공고)",
                "[접수마감게시] 화성시 병점복합타운 A-1블럭 행복주택 예비입주자 모집 관련(2022.08.05)")) {
            assertNotNull(OfficialFollowupRelationship.match(TARGET, parts(title, "당첨자 발표 변경 안내")).proof(), title);
        }
    }

    @Test
    void dateAndScopeCannotBeBorrowedAcrossAdjacentTableRows() {
        var result = OfficialFollowupRelationship.match(TARGET, parts("여러 모집 안내",
                "화성 병점 복합타운 A1블록 행복주택 (2022.08.06 모집공고)",
                "다른 단지 행복주택 (2022.08.05 모집공고)"));
        assertNull(result.proof());
        assertTrue(result.ambiguous());
    }

    @Test
    void oneMatchingRecruitmentDoesNotAdoptOtherRecruitmentRows() {
        var result = OfficialFollowupRelationship.match(TARGET, parts("여러 모집 안내",
                "화성 병점 복합타운 A1블록 행복주택 (2022.08.05 모집공고)",
                "다른 단지 행복주택 (2022.08.06 모집공고)"));
        assertNull(result.proof());
        assertTrue(result.ambiguous());
    }

    @Test
    void sameNameDifferentOriginalDateIsNotRelated() {
        var result = OfficialFollowupRelationship.match(TARGET, parts("화성 병점 복합타운 A1블록 행복주택 (2022.09.05 모집공고)"));
        assertNull(result.proof());
        assertFalse(result.ambiguous());
    }

    @Test
    void datesOnSchedulesAreNotOriginalPublicationDates() {
        var result = OfficialFollowupRelationship.match(TARGET, parts("화성 병점 복합타운 A1블록 행복주택 안내",
                "신청 접수 마감 2022.08.05 18:00입니다. 상세 공고를 참고하세요."));
        assertNull(result.proof());
        assertTrue(result.ambiguous());
    }

    @Test
    void multipleBlocksAndAudienceGroupsCannotBeImportedAsOneRecruitment() {
        assertTrue(OfficialFollowupRelationship.multipleRecruitments(TARGET, parts("화성 병점 복합타운 A1블록·A2블록 행복주택 (2022.08.05 공고)")));
        var youth = new Target(UUID.randomUUID(), 1, TARGET.primaryUrl(), "2026년 청년 전세임대 1순위 입주자 수시모집", LocalDate.parse("2026-02-24"));
        assertEquals("청년 전세임대", OfficialFollowupRelationship.queryTerm(youth).orElseThrow());
        assertTrue(OfficialFollowupRelationship.identity(youth.title()).scope().contains("청년"));
        assertTrue(OfficialFollowupRelationship.multipleRecruitments(youth, parts("청년·신혼부부 전세임대 (2026.02.24 공고)")));
        assertNull(OfficialFollowupRelationship.match(youth, parts("신혼부부 전세임대 1순위 (2026.02.24 공고)")).proof());
        assertNotNull(OfficialFollowupRelationship.match(youth, parts("청년 전세임대 1순위 접수 마감 (2026.02.24 모집공고)")).proof());
    }

    @Test
    void listParserReadsRealIdentifiersAndRejectsAmbiguousOrTruncatedRows() {
        var parsed = OfficialFollowupPageParser.search(OfficialFollowupDiscoveryTest.search(1, 1, 1, "후속 모집 공지", "9102829719"), 1);
        assertEquals(OfficialFollowupDiscoveryTest.PAGE, parsed.rows().get(0).url());
        assertEquals(LocalDate.parse("2022-08-17"), parsed.rows().get(0).publishedDate());
        assertThrows(IllegalArgumentException.class, () -> OfficialFollowupPageParser.search("<main>점검 중</main>", 1));
        assertThrows(IllegalArgumentException.class, () -> OfficialFollowupPageParser.search(OfficialFollowupDiscoveryTest.search(1, 1, 1, null, null), 1));
        assertThrows(IllegalArgumentException.class, () -> OfficialFollowupPageParser.search(OfficialFollowupDiscoveryTest.search(1, 1, 1, "후속", "1bad"), 1));
    }

    @Test
    void bodyParagraphsAndTableRowsKeepTheirSeparateProvenance() {
        String html = OfficialFollowupDiscoveryTest.page("일정 변경", "서류 일정 안내</p><table><tr><td>서류제출자 발표</td><td>8월23일</td></tr>"
                + "<tr><td>당첨자 발표</td><td>11월25일</td></tr></table><p>상세 원문 확인", "");
        var parts = OfficialFollowupPageParser.context(html, OfficialFollowupDiscoveryTest.PAGE);
        assertEquals("공식 후속 공지 제목", parts.get(0).location());
        assertEquals("공식 후속 공지 게시 정보", parts.get(1).location());
        assertTrue(parts.stream().anyMatch(p -> p.text().equals("서류제출자 발표 : 8월23일")));
        assertTrue(parts.stream().anyMatch(p -> p.text().equals("당첨자 발표 : 11월25일")));
        assertFalse(parts.stream().anyMatch(p -> p.text().contains("조회수")));
    }
}
