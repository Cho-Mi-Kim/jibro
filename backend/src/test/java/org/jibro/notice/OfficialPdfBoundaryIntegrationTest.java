package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.*;
import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OfficialPdfBoundaryIntegrationTest {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    AttachmentFingerprintClient files;
    private static final String PATH = "/api/v1/notices/official-pdf/123";
    private static final String SHA = "a".repeat(64);

    private AttachmentFingerprintClient.Download pdf() throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            byte[] bytes = out.toByteArray();
            return new AttachmentFingerprintClient.Download(new AttachmentFingerprintClient.Fingerprint(SHA, bytes.length, "application/pdf"), bytes);
        }
    }

    @Test
    void publicHttpPathReturnsRealPngAndPreservesChangedSourceAndMissingPageErrors() throws Exception {
        when(files.download(anyString())).thenReturn(pdf());
        var response = mvc.perform(get(PATH + "/page/1").param("sha256", SHA))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png")).andReturn();
        var image = ImageIO.read(new ByteArrayInputStream(response.getResponse().getContentAsByteArray()));
        assertNotNull(image);
        assertTrue(image.getWidth() <= 2400 && image.getHeight() <= 2400);
        mvc.perform(get(PATH + "/page/2")).andExpect(status().isNotFound());
        mvc.perform(get(PATH + "/page/1").param("sha256", "b".repeat(64)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_SOURCE_CHANGED"));
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
    }

    @Test
    void overflowHttpRequestIsRejectedBeforeDownloadAndHealthStaysAvailable() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var pdf = pdf();
        when(files.download(anyString())).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
            return pdf;
        });
        var pool = Executors.newFixedThreadPool(2);
        var results = new java.util.ArrayList<Future<Integer>>();
        try {
            for (int i = 0; i < 2; i++)
                results.add(pool.submit(() -> mvc.perform(get(PATH)).andReturn().getResponse().getStatus()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            mvc.perform(get(PATH + "/page/1")).andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("OFFICIAL_DOCUMENT_BUSY"));
            verify(files, times(2)).download(anyString());
            mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        } finally {
            release.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        for (var result : results) assertEquals(200, result.get());
    }
}
