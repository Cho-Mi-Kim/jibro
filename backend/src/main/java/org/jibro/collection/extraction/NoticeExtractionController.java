package org.jibro.collection.extraction;

import java.util.*;

import org.jibro.global.common.ApiException;
import org.springframework.web.bind.annotation.*;

import static org.jibro.collection.extraction.ExtractionModels.*;

@RestController
@RequestMapping("/api/v1/admin/reviews/{noticeId}/extractions")
public class NoticeExtractionController {
    private final ExtractionStore store;
    private final NoticeExtractionService service;

    public NoticeExtractionController(ExtractionStore store, NoticeExtractionService service) {
        this.store = store;
        this.service = service;
    }

    @GetMapping
    List<Draft> list(@PathVariable UUID noticeId) {
        return store.recent(noticeId);
    }

    @PostMapping
    Draft start(@PathVariable UUID noticeId) {
        return store.enqueue(noticeId, true);
    }

    public record Adopt(int revision) {
    }

    @PostMapping("/{draftId}/attachments")
    Map<String, Boolean> adopt(@PathVariable UUID noticeId, @PathVariable UUID draftId, @RequestBody Adopt body) {
        service.adoptAttachments(noticeId, draftId, body.revision());
        return Map.of("saved", true);
    }

    public record File(String url) {
    }

    @PostMapping("/{draftId}/file")
    Source extractFile(@PathVariable UUID noticeId, @PathVariable UUID draftId, @RequestBody File body) {
        var draft = store.find(draftId);
        if (!draft.noticeId().equals(noticeId.toString())) throw ApiException.notFound();
        var source = draft.sources().stream().filter(s -> Objects.equals(s.url(), body.url()) && !s.label().equals("공식 상세 본문")).findFirst().orElseThrow(ApiException::notFound);
        return store.updateFile(noticeId, draftId, service.extract(new Link(source.label(), source.url())));
    }
}
