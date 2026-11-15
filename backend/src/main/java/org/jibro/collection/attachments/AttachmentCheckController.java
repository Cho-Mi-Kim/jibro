package org.jibro.collection.attachments;

import java.util.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/reviews/{id}/attachment-checks")
public class AttachmentCheckController {
    private final AttachmentCheckService service;
    private final AttachmentCheckStore store;

    public AttachmentCheckController(AttachmentCheckService service, AttachmentCheckStore store) {
        this.service = service;
        this.store = store;
    }

    @GetMapping
    List<AttachmentCheckStore.Run> recent(@PathVariable UUID id) { return store.recent(id); }

    @PostMapping
    AttachmentCheckStore.Run check(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.check(id, jwt.getSubject());
    }
}
