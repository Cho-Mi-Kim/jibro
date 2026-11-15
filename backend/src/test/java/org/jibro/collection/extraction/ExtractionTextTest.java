package org.jibro.collection.extraction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.ExtractionModels.Link;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.junit.jupiter.api.Test;

class ExtractionTextTest {
    @Test void validKoreanSupplementaryCharactersAndLineBreaksRemainUnchanged() {
        String text = "신청 자격 확인 🏠\n소득\t자산\r\n";
        var parts = List.of(new Part("공고문 2쪽", text));
        assertDoesNotThrow(() -> ExtractionText.parts(parts));
        assertEquals(text, parts.get(0).text());
    }

    @Test void nulAndUnpairedSurrogatesAreRejectedWithoutEchoingTheSource() {
        for (String invalid : List.of("비공개" + (char) 0 + "내용", "비공개" + '\uD800', "비공개" + '\uDC00', "\uD800A")) {
            var failure = assertThrows(ExtractionText.Invalid.class, () -> ExtractionText.parts(List.of(new Part("2쪽", invalid))));
            assertFalse(failure.getMessage().contains("비공개"));
            assertThrows(ExtractionText.Invalid.class, () -> ExtractionText.html("<main>" + invalid + "</main>"));
        }
        assertThrows(ExtractionText.Invalid.class, () -> ExtractionText.parts(List.of(new Part("2쪽" + (char) 0, "신청 자격"))));
    }

    @Test void sourceValidationHasACombinedTextBudget() {
        String page = "가".repeat(500_001);
        assertThrows(ExtractionText.Invalid.class, () -> ExtractionText.parts(List.of(new Part("1쪽", page), new Part("2쪽", page))));
        assertThrows(ExtractionText.Invalid.class, () -> ExtractionText.parts(java.util.Collections.nCopies(1001, new Part("1쪽", ""))));
    }

    @Test void invalidDocumentNeverBecomesExtractedOrKeepsUnsafeContext() throws Exception {
        var http = mock(AttachmentFingerprintClient.class);
        var parser = mock(BoundedDocumentParser.class);
        when(http.download(anyString())).thenReturn(new AttachmentFingerprintClient.Download(
            new AttachmentFingerprintClient.Fingerprint("hash", 8, "application/pdf"), new byte[8]));
        var service = new NoticeExtractionService(null, null, http, parser, true);
        for (String bad : List.of("신청 자격" + (char) 0 + "소득", "신청 자격" + '\uD800')) {
            when(parser.parse(any(byte[].class), anyString())).thenReturn(List.of(new Part("2쪽", bad)));
            var result = service.extract(new Link("공고문", "https://apply.lh.or.kr/source.pdf"));
            assertEquals("FAILED", result.status());
            assertEquals(ExtractionText.FAILURE_MESSAGE, result.message());
            assertTrue(result.context().isEmpty());
            assertTrue(result.excerpts().isEmpty());
        }
    }

    @Test void actualTextWithoutReviewKeywordsIsExtractedWithoutInventingClassifiedExcerpts() throws Exception {
        var http = mock(AttachmentFingerprintClient.class);
        var parser = mock(BoundedDocumentParser.class);
        when(http.download(anyString())).thenReturn(new AttachmentFingerprintClient.Download(
            new AttachmentFingerprintClient.Fingerprint("a".repeat(64), 8, "application/zip"), new byte[8]));
        var parts = List.of(new Part("Excel 워크시트 주택목록 (행·열 위치)", "A1: 주택목록\nA2: 서울\nB2: 101호\nC2: 1,234,500"));
        when(parser.parse(any(byte[].class), anyString())).thenReturn(parts);
        var service = new NoticeExtractionService(null, null, http, parser, true);

        var result = service.extract(new Link("주택목록.xlsx", "https://apply.lh.or.kr/source.xlsx"));

        assertEquals("EXTRACTED", result.status());
        assertEquals(parts, result.context());
        assertEquals("a".repeat(64), result.sha256());
        assertTrue(result.excerpts().isEmpty());
        assertTrue(result.message().contains("자동 분류되지 않아"));
    }

    @Test void blankAndMissingPartsRemainEmptyRatherThanSuccessfullyRead() throws Exception {
        var http = mock(AttachmentFingerprintClient.class);
        var parser = mock(BoundedDocumentParser.class);
        when(http.download(anyString())).thenReturn(new AttachmentFingerprintClient.Download(
            new AttachmentFingerprintClient.Fingerprint("a".repeat(64), 8, "application/pdf"), new byte[8]));
        var service = new NoticeExtractionService(null, null, http, parser, true);
        for (List<Part> parts : List.of(List.<Part>of(), List.of(new Part("공고문 1쪽", " \n\t")))) {
            when(parser.parse(any(byte[].class), anyString())).thenReturn(parts);
            var result = service.extract(new Link("공고문", "https://apply.lh.or.kr/source.pdf"));
            assertEquals("EMPTY", result.status());
            assertTrue(result.excerpts().isEmpty());
            assertTrue(result.message().contains("원문 텍스트를 확인하지 못했습니다"));
        }
    }
}
