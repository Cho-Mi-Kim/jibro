package org.jibro.collection.review.followup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.collection.attachments.AttachmentFingerprintClient.Fingerprint;
import org.jibro.collection.extraction.ExtractionModels.*;
import org.jibro.collection.extraction.OfficialDetailParser;
import org.jibro.notice.HousingModels.*;
import org.junit.jupiter.api.Test;

class FollowupPrimaryVerifierTest {
    private final String url = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=123";
    private final String file = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123";
    private final String hash = "a".repeat(64);
    private final String html = "<main><p>공식 조건입니다.</p><a href='" + file + "'>공고문.pdf</a></main>";
    private final AttachmentFingerprintClient http = mock(AttachmentFingerprintClient.class);
    private final FollowupPrimaryVerifier verifier = new FollowupPrimaryVerifier(http);

    private Content original() {
        return new Content("", new Evidence("원공고", url, "2026-11-09"),
            List.of(new Evidence("공고문.pdf", file, "2026-11-09")), List.of(), List.of(), List.of());
    }

    private Draft draft() {
        return new Draft("id", "notice", 1, "READY", Instant.now(), "",
            List.of(new Source("공식 상세", url, hash, "EXTRACTED", "", List.of(), OfficialDetailParser.parse(html,url).parts()),
                new Source("공고문.pdf", file, hash, "EXTRACTED", "", List.of(), List.of(new Part("1쪽", "본문")))));
    }

    @Test void everyPrimaryAttachmentIsRefetchedBeforePublishing() throws Exception {
        when(http.html(url)).thenReturn(html);
        when(http.fetch(file)).thenReturn(new Fingerprint(hash, 10, "application/pdf"));
        assertNotNull(verifier.verify(draft(), original()));
        verify(http).fetch(file);
    }

    @Test void changedBytesAtTheSameOfficialUrlBlockPublication() throws Exception {
        when(http.html(url)).thenReturn(html);
        when(http.fetch(file)).thenReturn(new Fingerprint("b".repeat(64),10,"application/pdf"));
        assertEquals("PRIMARY_ATTACHMENT_CHANGED", assertThrows(IllegalArgumentException.class,
            () -> verifier.verify(draft(), original())).getMessage());
    }

    @Test void changedDetailCannotBeCoveredByAnUnrelatedFollowup() throws Exception {
        when(http.html(url)).thenReturn("<main><p>새 조건입니다.</p></main>");
        assertEquals("PRIMARY_DETAIL_CHANGED", assertThrows(IllegalArgumentException.class,
            () -> verifier.verify(draft(), original())).getMessage());
        verify(http, never()).fetch(anyString());
    }
}
