package org.jibro.notice;

import org.jibro.collection.attachments.AttachmentFingerprintClient;
import org.jibro.global.common.ApiException;
import org.jibro.collection.extraction.BoundedDocumentParser;
import org.jibro.collection.extraction.EvidenceRelocationStore;
import org.jibro.collection.extraction.PdfPageRenderer;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.Map;

/**
 * Displays a bounded official LH PDF inline; never fetches a caller-supplied URL.
 */
@RestController
public class OfficialPdfController {
    private final AttachmentFingerprintClient files;
    private final BoundedDocumentParser parser;
    private final EvidenceRelocationStore relocation;

    public OfficialPdfController(AttachmentFingerprintClient files, BoundedDocumentParser parser, EvidenceRelocationStore relocation) {
        this.files = files;
        this.parser = parser;
        this.relocation = relocation;
    }

    @GetMapping("/api/v1/notices/official-pdf/{fileId}")
    public ResponseEntity<byte[]> pdf(@PathVariable String fileId) throws Exception {
        if (!fileId.matches("[0-9]{1,20}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var file = files.download("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + fileId);
        var bytes = file.bytes();
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-')
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PDF가 아닌 공식 첨부 자료입니다.");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=official-notice.pdf")
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    @GetMapping("/api/v1/notices/official-pdf/{fileId}/page/{page}")
    public ResponseEntity<byte[]> page(@PathVariable String fileId, @PathVariable int page, @RequestParam(required = false) String sha256) throws Exception {
        if (!fileId.matches("[0-9]{1,20}") || page < 1 || page > 1000 || (sha256 != null && !sha256.matches("[0-9a-f]{64}")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var downloaded = files.download("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + fileId);
        if (sha256 != null && !sha256.equals(downloaded.fingerprint().sha256()))
            throw new ApiException(409, "OFFICIAL_SOURCE_CHANGED", "원문이 변경되어 재확인이 필요합니다.");
        byte[] bytes = downloaded.bytes();
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-')
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        try {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(parser.renderPdfPage(bytes, page));
        } catch (PdfPageRenderer.PageException failure) {
            throw new ResponseStatusException(failure.missing() ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY);
        } catch (RejectedExecutionException failure) {
            throw new ApiException(429, "OFFICIAL_DOCUMENT_BUSY", "공고문을 처리 중이에요. 잠시 후 다시 열어 주세요.");
        } catch (IOException | TimeoutException failure) {
            throw new ApiException(503, "OFFICIAL_DOCUMENT_UNAVAILABLE", "공고문 화면을 만들지 못했어요. 잠시 후 다시 열거나 PDF 원본을 확인해 주세요.");
        }
    }

    /**
     * Only serves text from the current official attachment, bound to its checked digest.
     */
    @GetMapping("/api/v1/notices/official-pdf/{fileId}/text")
    public ResponseEntity<Map<String, String>> text(@PathVariable String fileId, @RequestParam String sha256, @RequestParam(required = false) Integer part, @RequestParam(required = false) String location) throws Exception {
        if (!fileId.matches("[0-9]{1,20}") || !sha256.matches("[0-9a-f]{64}") || (part == null && (location == null || location.isBlank() || location.length() > 300))
                || (part != null && (part < 0 || part >= 1000)) || (part != null && location != null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var file = files.download("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + fileId);
        if (!sha256.equals(file.fingerprint().sha256()))
            throw new ApiException(409, "OFFICIAL_SOURCE_CHANGED", "원문이 변경되어 재확인이 필요합니다.");
        if (!java.util.Set.of("application/zip", "application/x-ole-storage").contains(file.fingerprint().mediaType()))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        var parts = parser.parse(file.bytes(), file.fingerprint().mediaType());
        org.jibro.collection.extraction.ExtractionModels.Part selected;
        if (part != null) {
            if (part >= parts.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            selected = parts.get(part);
        } else {
            var matches = parts.stream().filter(value -> location.equals(value.location())).toList();
            if (matches.size() != 1) throw new ApiException(409, "OFFICIAL_LOCATION_CHANGED", "원문 위치를 다시 확인해야 합니다.");
            selected = matches.get(0);
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("location", selected.location(), "text", selected.text()));
    }

    /**
     * Returns location digests only for the exact checked official file.
     */
    @GetMapping("/api/v1/notices/official-pdf/{fileId}/manifest")
    public ResponseEntity<Map<String, Object>> manifest(@PathVariable String fileId, @RequestParam String sha256) throws Exception {
        if (!fileId.matches("[0-9]{1,20}") || !sha256.matches("[0-9a-f]{64}"))
            throw new ApiException(400, "INVALID_INPUT", "자료 경로를 확인해 주세요.");
        var file = files.download("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + fileId);
        if (!sha256.equals(file.fingerprint().sha256()))
            throw new ApiException(409, "OFFICIAL_SOURCE_CHANGED", "원문이 변경되어 재확인이 필요합니다.");
        var parts = parser.parse(file.bytes(), file.fingerprint().mediaType());
        var entries = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            if (part.text() != null && !part.text().isBlank() && !org.jibro.collection.extraction.OcrProvenance.contains(part))
                entries.add(Map.of("part", i, "location", part.location(), "digest", EvidencePartMatcher.digest(part.text())));
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of("parts", entries));
    }

    /**
     * Reads a result already calculated by the background extraction worker.
     */
    @GetMapping("/api/v1/notices/official-pdf/{fileId}/relocate")
    public ResponseEntity<Map<String, Object>> relocate(@PathVariable String fileId, @RequestParam String baselineSha, @RequestParam String partDigest) {
        if (!fileId.matches("[0-9]{1,20}") || !baselineSha.matches("[0-9a-f]{64}") || !partDigest.matches("[0-9a-f]{64}"))
            throw new ApiException(400, "INVALID_INPUT", "자료 경로를 확인해 주세요.");
        return relocation.resolved(fileId, baselineSha, partDigest)
                .map(value -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(value))
                .orElseThrow(() -> new ApiException(409, "OFFICIAL_LOCATION_PENDING", "변경된 원문의 위치를 확인 중이거나 추가 확인이 필요합니다."));
    }

    /**
     * A live digest lets a cached evidence extract detect changes before it is shown.
     */
    @GetMapping("/api/v1/notices/official-pdf/{fileId}/fingerprint")
    public Map<String, String> fingerprint(@PathVariable String fileId) throws Exception {
        if (!fileId.matches("[0-9]{1,20}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var file = files.fetchForSourceReview("https://apply.lh.or.kr/lhapply/lhFile.do?fileid=" + fileId);
        return Map.of("sha256", file.sha256());
    }
}
