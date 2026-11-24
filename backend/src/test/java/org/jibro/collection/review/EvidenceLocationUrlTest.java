package org.jibro.collection.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

import org.jibro.collection.extraction.ExtractionModels.Source;
import org.jibro.collection.extraction.ExtractionModels.Part;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceLocationUrlTest {
    @Test
    void addsOnlyVerifiedTopLevelPdfPage() {
        String url = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
        assertEquals(url + "#page=4", EvidenceLocationUrl.forPart("공고.pdf", url, "공고문 4쪽"));
        assertEquals(url, EvidenceLocationUrl.forPart("첨부.hwpx", url, "공고문 4쪽"));
        assertEquals(url, EvidenceLocationUrl.forPart("공고.pdf", url, "내부파일 · 공고문 4쪽"));
        assertEquals(url, EvidenceLocationUrl.forPart("공고.pdf", url, "공고문 4~5쪽"));
        String unsafe = "https://example.com/lhapply/lhFile.do?fileid=123";
        assertEquals(unsafe, EvidenceLocationUrl.forPart("공고.pdf", unsafe, "공고문 4쪽"));
    }

    @Test
    void connectsUniqueDocumentTextOnlyToTheCheckedSource() {
        String url = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
        String sha = "a".repeat(64);
        String text = "첫 항목입니다.\n입주 신청 자격은 공고 기준을 따릅니다.\n다른 항목입니다.";
        var part = new Part("HWPX section 1", text);
        for (String name : List.of("공고.hwp", "공고.hwpx", "명단.xls", "명단.xlsx")) {
            var source = new Source(name, url, sha, "READY", "", List.of(), List.of(part));
            assertTrue(EvidenceLocationUrl.forSource(source, part.location(), "입주 신청 자격은 공고 기준을 따릅니다.").startsWith(url + "#doc2=" + sha + "."));
            assertEquals(url, EvidenceLocationUrl.forSource(source, "없는 위치", text));
        }
        var nestedPart = new Part("위임장.hwp · HWP Section0", text);
        var zip = new Source("서식.zip", url, sha, "READY", "", List.of(), List.of(nestedPart));
        assertTrue(EvidenceLocationUrl.forSource(zip, nestedPart.location(), text).startsWith(url + "#doc2="));
        assertEquals(url, EvidenceLocationUrl.forSource(zip, "unknown", text));
        var pdf = new Source("공고.pdf", url, sha, "READY", "", List.of(), List.of(new Part("공고문 4쪽", text)));
        assertEquals(url + "#page=4&sha256=" + sha, EvidenceLocationUrl.forSource(pdf, "공고문 4쪽", text));
        var projected = new Source("공고.hwpx", url, sha, "READY", "", List.of(), List.of(new Part("가공된 첫 항목", "ignore"), part));
        var original = new Source("공고.hwpx", url, sha, "READY", "", List.of(), List.of(part));
        assertEquals(EvidenceLocationUrl.forSource(original, part.location(), text), EvidenceLocationUrl.forSource(projected, part.location(), text));
        var repeated = new Source("공고.hwp", url, sha, "READY", "", List.of(), List.of(new Part("section", "동일한 신청 자격 안내입니다.\n동일한 신청 자격 안내입니다.")));
        assertEquals(url, EvidenceLocationUrl.forSource(repeated, "section", "동일한 신청 자격 안내입니다."));
        var unsafe = new Source("공고.hwp", "https://example.com/file", sha, "READY", "", List.of(), List.of(part));
        assertEquals(unsafe.url(), EvidenceLocationUrl.forSource(unsafe, part.location(), text));
    }
}
