package org.jibro.notice;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoticeVersionService {
    private final NoticeService notices;
    private final NoticeVersionRepository repository;

    public NoticeVersionService(NoticeService notices, NoticeVersionRepository repository) {
        this.notices = notices;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<NoticeVersion> versions(UUID id) {
        var current = notices.notice(id);
        return repository.findPublished(id, current.guideState().equals("AVAILABLE"));
    }
}
