package org.jibro.notice;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.extraction.BoundedDocumentParser;
import org.jibro.collection.extraction.EvidenceRelocationStore;
import org.jibro.collection.extraction.ExtractionModels.Part;
import org.jibro.global.common.ApiException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OfficialDocumentTextTest {
    @Test void servesOnlyCheckedBytesAndRejectsChangedFile() throws Exception {
        var client = mock(AttachmentFingerprintClient.class);
        var parser = mock(BoundedDocumentParser.class);
        var controller = new OfficialPdfController(client, parser, mock(EvidenceRelocationStore.class));
        String sha = "a".repeat(64), url = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
        byte[] bytes = {1, 2, 3};
        when(client.download(url)).thenReturn(new AttachmentFingerprintClient.Download(new AttachmentFingerprintClient.Fingerprint(sha, 3, "application/zip"), bytes));
        when(parser.parse(bytes, "application/zip")).thenReturn(List.of(new Part("section", "공식 문서 내용")));
        assertEquals("공식 문서 내용", controller.text("123", sha, 0, null).getBody().get("text"));
        assertEquals(409, assertThrows(ApiException.class, () -> controller.text("123", "b".repeat(64), 0, null)).status);
        assertEquals("공식 문서 내용", controller.text("123", sha, null, "section").getBody().get("text"));
        assertEquals(409, assertThrows(ApiException.class, () -> controller.text("123", sha, null, "unknown")).status);
        assertEquals(409, assertThrows(ApiException.class, () -> controller.page("123", 1, "b".repeat(64))).status);
        assertThrows(ResponseStatusException.class, () -> controller.text("../x", sha, 0, null));
    }

    @Test void viewerReadsOnlyPreviouslyComputedResultWithoutDownloadingOrParsing() throws Exception {
        var client = mock(AttachmentFingerprintClient.class);
        var parser = mock(BoundedDocumentParser.class);
        var store = mock(EvidenceRelocationStore.class);
        var controller = new OfficialPdfController(client, parser, store);
        String oldSha = "a".repeat(64), hash = "c".repeat(64), newSha = "b".repeat(64);
        when(store.resolved("123", oldSha, hash)).thenReturn(Optional.of(Map.of("sha256", newSha, "part", 1, "location", "새 위치")));
        assertEquals("새 위치", controller.relocate("123", oldSha, hash).getBody().get("location"));
        assertEquals(409, assertThrows(ApiException.class, () -> controller.relocate("123", oldSha, "d".repeat(64))).status);
        verifyNoInteractions(client, parser);
    }
}
