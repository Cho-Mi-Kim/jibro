package org.jibro.notice;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NoticeComparisonController {
    private final NoticeComparisonService service;

    public NoticeComparisonController(NoticeComparisonService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/notices/{id}/comparison")
    public NoticeComparisonService.Comparison comparison(@PathVariable UUID id) {
        return service.comparison(id);
    }
}
