package org.jibro.notice;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static org.jibro.notice.HousingModels.*;

@Service
@Transactional(readOnly = true)
public class NoticeComparisonService {
    public record Comparison(Notice notice, List<DocumentTemplate> documentRequirements) {
    }

    private final NoticeService notices;

    public NoticeComparisonService(NoticeService notices) {
        this.notices = notices;
    }

    public Comparison comparison(UUID id) {
        var notice = notices.notice(id);
        return new Comparison(notice, notice.documentTemplates());
    }
}
