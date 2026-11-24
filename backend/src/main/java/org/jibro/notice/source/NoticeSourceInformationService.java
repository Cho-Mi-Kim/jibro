package org.jibro.notice.source;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jibro.notice.source.NoticeSourceInformationModels.Information;

@Service
@Transactional(readOnly = true)
public class NoticeSourceInformationService {
    private final NoticeSourceInformationRepository repository;
    private final Set<String> allowedHosts;

    public NoticeSourceInformationService(NoticeSourceInformationRepository repository,
                                          @Value("${app.collection.attachment-hosts:apply.lh.or.kr,www.lh.or.kr}") String hosts) {
        this.repository = repository;
        this.allowedHosts = Set.copyOf(Arrays.stream(hosts.split(",")).map(String::trim)
            .filter(host -> !host.isEmpty()).map(host -> host.toLowerCase(Locale.ROOT)).toList());
    }

    public Information get(UUID noticeId) {
        int revision = repository.publishedRevision(noticeId);
        var snapshot = repository.latest(noticeId, revision);
        if (snapshot.isPresent() && !repository.current(snapshot.get())) return Information.pending(noticeId);
        var information = snapshot.map(current -> NoticeSourceExcerptReader.read(current, allowedHosts)).orElseGet(() -> Information.pending(noticeId));
        if (snapshot.isPresent() && !repository.current(snapshot.get())) return Information.pending(noticeId);
        var items = repository.confirmedItems(noticeId, revision);
        return new Information(information.noticeId(), information.checkedAt(), information.state(), information.sections(), information.pendingAreas(), items);
    }
}
