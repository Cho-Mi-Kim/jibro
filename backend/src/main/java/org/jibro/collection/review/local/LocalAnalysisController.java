package org.jibro.collection.review.local;

import java.util.UUID;

import org.springframework.web.bind.annotation.*;

/**
 * Existing /admin security boundary protects local evidence and advisory API outcomes.
 */
@RestController
@RequestMapping("/api/v1/admin/reviews/{noticeId}/extractions/{extractionId}/local-analysis")
public class LocalAnalysisController {
    private final LocalAnalysisService service;

    public LocalAnalysisController(LocalAnalysisService service) {
        this.service = service;
    }

    @GetMapping
    LocalAnalysisRepository.Entry get(@PathVariable UUID noticeId, @PathVariable UUID extractionId) {
        return service.get(noticeId, extractionId);
    }
}
