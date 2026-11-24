package org.jibro.notice.source;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.jibro.notice.source.NoticeSourceInformationModels.Information;

@RestController
@RequestMapping("/api/v1/notices/{noticeId}/source-information")
public class NoticeSourceInformationController {
    private final NoticeSourceInformationService service;

    public NoticeSourceInformationController(NoticeSourceInformationService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<Information> get(@PathVariable UUID noticeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(noticeId));
    }
}
