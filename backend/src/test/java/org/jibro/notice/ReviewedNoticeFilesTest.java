package org.jibro.notice;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.jibro.notice.HousingModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewedNoticeFilesTest {
    @Test
    void attachmentsAreScopedToTheReviewedVersionAndDeduplicated() {
        String id = "cb715a71-3611-4d8a-a23f-573d0bae22e9";
        var files = ReviewedNoticeFiles.attachments(id, 8, List.of());
        assertEquals(1, files.size());
        assertEquals(files, ReviewedNoticeFiles.attachments(id, 8, files));
        assertTrue(ReviewedNoticeFiles.attachments(id, 9, List.of()).isEmpty());
    }

    @Test
    void formRequiresMatchingDocumentAndPublishedAttachmentInTheSameVersion() {
        String id = "65d673cf-5528-43fb-9f4e-82ca5357879b";
        var source = new Evidence("상시모집서류양식.zip", "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=67181892", "2026-11-15");
        var template = new DocumentTemplate("privacy", "개인정보 수집·이용 및 제3자 제공 동의서", null, "제출 기준", "LH", null, source);
        var docs = List.of(template);
        var linked = ReviewedNoticeFiles.documents(id, 3, docs, List.of(source)).get(0);
        assertEquals(source.url(), linked.issueUrl());
        assertEquals(template.issuer(), linked.issuer());
        assertTrue(linked.issueLabel().contains("[양식2]"));
        assertEquals(docs, ReviewedNoticeFiles.documents(id, 3, docs, List.of()));
        assertEquals(docs, ReviewedNoticeFiles.documents(id, 4, docs, List.of(source)));
        assertEquals(List.of(linked), ReviewedNoticeFiles.documents(id, 3, List.of(linked), List.of(source)));
    }
}
