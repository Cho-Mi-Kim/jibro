package org.jibro.notice;

import java.util.*;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notices")
public class NoticeVersions {
    private final NoticeVersionService versions;

    public NoticeVersions(NoticeVersionService versions) {
        this.versions = versions;
    }

    @GetMapping("/{id}/versions")
    List<NoticeVersion> versions(@PathVariable UUID id) {
        return versions.versions(id);
    }
}
