package org.jibro.member;

import java.util.List;
import java.util.UUID;

import org.jibro.global.common.ApiException;
import org.jibro.notice.NoticeService;
import org.jibro.notice.NoticeListItem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedNoticeService {
    private final SavedNoticeRepository repository;
    private final NoticeService notices;

    public SavedNoticeService(SavedNoticeRepository repository, NoticeService notices) {
        this.repository = repository;
        this.notices = notices;
    }

    public List<NoticeListItem> list(UUID user) {
        return notices.listByIds(repository.findOwned(user));
    }

    @Transactional
    public void set(UUID user, UUID notice, boolean saved) {
        if (!saved) {
            repository.remove(user, notice);
            return;
        }
        var version = repository.lockPublicVersion(notice).orElseThrow(ApiException::notFound);
        repository.save(user, notice, version);
    }
}
