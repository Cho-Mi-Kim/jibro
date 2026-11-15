package org.jibro.notice;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notices")
public class NoticeCorrectionController {
    private final NoticeCorrectionService service;

    public NoticeCorrectionController(NoticeCorrectionService service) {
        this.service = service;
    }

    @GetMapping("/{id}/correction")
    public ResponseEntity<NoticeCorrectionService.Correction> correction(@PathVariable UUID id) {
        return service.correction(id).map(value -> ResponseEntity.ok().cacheControl(
            org.springframework.http.CacheControl.noStore()).body(value))
            .orElseGet(() -> ResponseEntity.noContent().cacheControl(org.springframework.http.CacheControl.noStore()).build());
    }
}
