package org.jibro.notice;

import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Part;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvidencePartMatcherTest {
    @Test
    void findsMovedUnchangedPartAndRejectsChangedOrDuplicatedParts() {
        var original = new Part("공고문 3쪽", "신청 내용과 조건을 함께 확인합니다.");
        String hash = EvidencePartMatcher.digest(original.text());
        var moved = new Part("공고문 4쪽", original.text());
        assertEquals(new EvidencePartMatcher.Match(1, "공고문 4쪽"), EvidencePartMatcher.match(List.of(new Part("공고문 3쪽", "다른 내용"), moved), hash));
        assertNull(EvidencePartMatcher.match(List.of(new Part("공고문 4쪽", original.text() + " 변경")), hash));
        assertNull(EvidencePartMatcher.match(List.of(original, moved), hash));
        assertNull(EvidencePartMatcher.match(List.of(new Part("[OCR · 원문 대조 필요]", original.text())), hash));
        assertNull(EvidencePartMatcher.match(List.of(original, new Part("empty", "")), hash));
        assertNull(EvidencePartMatcher.match(List.of(original, new Part("truncated", "[발췌 길이 제한]")), hash));
    }
}
