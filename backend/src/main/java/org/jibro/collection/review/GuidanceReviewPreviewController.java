package org.jibro.collection.review;

import java.util.UUID;

import org.springframework.web.bind.annotation.*;

/**
 * Admin-only read. Does not publish, edit a draft, or invoke an external model.
 */
@RestController
@RequestMapping("/api/v1/admin/reviews/{noticeId}/extractions/{extractionId}/guidance-preview")
public class GuidanceReviewPreviewController {
    private final AutomaticReviewService service;

    public GuidanceReviewPreviewController(AutomaticReviewService service) {
        this.service = service;
    }

    @GetMapping
    GuidanceReviewCompiler.Assessment get(@PathVariable UUID noticeId, @PathVariable UUID extractionId) {
        return service.preview(noticeId, extractionId);
    }
}
