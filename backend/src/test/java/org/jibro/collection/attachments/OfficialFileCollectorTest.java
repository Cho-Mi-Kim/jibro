package org.jibro.collection.attachments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.jibro.collection.LhNoticeClient;
import org.jibro.notice.HousingModels.DocumentTemplate;
import org.jibro.notice.OfficialFileManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class OfficialFileCollectorTest {
    private static final String URL = "https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?panId=123&ccrCnntSysDsCd=03&uppAisTpCd=06&aisTpCd=07";
    private static final String FILE = "https://apply.lh.or.kr/lhapply/lhFile.do?fileid=";
    private static final String MEMBER = "서식/[양식3]자산보유사실확인서.hwp";
    @TempDir Path temp;

    @Test
    void matchingUsesRealKoreanDirectoryForNewNoticeAndPreservesOtherIssuers() throws Exception {
        var archive = temp.resolve("forms.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive), Charset.forName("MS949"))) {
            for (String name : List.of(MEMBER, "../자산보유사실확인서.hwp", "작성예시/자산보유사실확인서.hwp")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.closeEntry();
            }
        }
        var store = mock(OfficialFileRepository.class);
        var http = mock(AttachmentFingerprintClient.class);
        var json = new ObjectMapper();
        var job = new OfficialFileRepository.Job(UUID.randomUUID(), URL, LhNoticeClient.sourceKey(URL), UUID.randomUUID());
        when(http.html(URL)).thenReturn("""
            <form id="wrtancViewForm"><input name="panId" value="123"></form>
            <a href="javascript: fileDownLoad( '123' );">공식첨부.zip</a>
            <a href="/lhapply/lhFile.do?fileid=456">별도도면.jpg</a>
            """);
        when(http.zipMembers(FILE+"123")).thenReturn(ZipFormDirectory.read(archive));
        new OfficialFileCollector(store, http, json).refresh(job);
        var saved = ArgumentCaptor.forClass(String.class);
        verify(store).finish(eq(job), saved.capture(), eq(false));
        var live = json.readValue(saved.getValue(), OfficialFileManifest.class);
        assertEquals(2, live.attachments(List.of()).size());
        var form = new DocumentTemplate("new", "자산 보유 사실확인서", null, null, null, null, null);
        var manual = new DocumentTemplate("other", form.title(), null, null, null, "https://www.gov.kr", null);
        var docs = live.documents(List.of(form, manual));
        assertEquals(FILE+"123", docs.get(0).issueUrl());
        assertTrue(docs.get(0).issueLabel().contains(MEMBER));
        assertEquals(manual, docs.get(1));
        var duplicate = new OfficialFileManifest("today", List.of(live.files().get(0),
            new OfficialFileManifest.File("자산보유사실확인서.hwp", FILE+"789", List.of())));
        assertNull(duplicate.documents(List.of(form)).get(0).issueUrl());
    }

    @Test
    void knownLhFilenamesAlsoMatchNewDocumentIdsWithoutNoticeSpecificMappings() throws Exception {
        try (var input = getClass().getResourceAsStream("/reviewed-notice-files.json")) {
            var notices = new ObjectMapper().readTree(input);
            int checked = 0;
            for (var notice : notices) for (var form : notice.path("forms")) {
                String url = form.path("url").asText();
                var members = form.path("member").isString() ? List.of(form.path("member").asText()) : List.<String>of();
                var manifest = new OfficialFileManifest("today",
                    List.of(new OfficialFileManifest.File(form.path("label").asText(), url, members)));
                var template = new DocumentTemplate("new-document", form.path("title").asText(), null, null, null, null, null);
                assertEquals(url, manifest.documents(List.of(template)).get(0).issueUrl(), template.title());
                checked++;
            }
            assertTrue(checked >= 17);
        }
    }

    @Test
    void wrongPageCannotReplaceLastInventoryAndUnreadableZipStillAppears() throws Exception {
        var store = mock(OfficialFileRepository.class);
        var http = mock(AttachmentFingerprintClient.class);
        var job = new OfficialFileRepository.Job(UUID.randomUUID(), URL, LhNoticeClient.sourceKey(URL), UUID.randomUUID());
        var json = new ObjectMapper();
        var worker = new OfficialFileCollector(store, http, json);
        when(http.html(URL)).thenReturn("<p>점검 중</p>", """
            <form id="wrtancViewForm"><input name="panId" value="123"></form>
            <button onclick="fileDownLoad('123')">양식.zip</button>
            """);
        when(http.zipMembers(FILE+"123")).thenThrow(new IOException());
        worker.refresh(job);
        verify(store).finish(job, null, true);
        clearInvocations(store);
        worker.refresh(job);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(store).finish(eq(job), payload.capture(), eq(true));
        assertEquals(FILE+"123", json.readValue(payload.getValue(), OfficialFileManifest.class).files().get(0).url());
    }
}
