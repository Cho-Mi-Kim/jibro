package org.jibro.notice;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jibro.global.common.ApiException;
import org.jibro.notice.HousingModels.Content;
import org.jibro.notice.HousingModels.Evidence;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Copies review sources inside a short transaction; external requests never hold DB locks.
 */
@Service
public class EvidenceSourceReader {
    public record Source(String kind, String label, String url, String checkedAt) {
    }

    public record Snapshot(int revision, int version, List<Source> sources) {
    }

    private final NoticeRepository notices;
    private final ObjectMapper json;

    public EvidenceSourceReader(NoticeRepository notices, ObjectMapper json) {
        this.notices = notices;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Snapshot read(UUID id, int revision) {
        var notice = notices.findById(id).orElseThrow(ApiException::notFound);
        if (notice.revision != revision) throw ApiException.conflict();
        var content = json.readValue(notice.content, Content.class);
        var sources = new ArrayList<Source>();
        add(sources, "NOTICE", content.evidence());
        content.attachments().forEach(evidence -> add(sources, "ATTACHMENT", evidence));
        content.questions().forEach(question -> add(sources, "CONDITION", question.title(), question.evidence()));
        content.documents().forEach(document -> {
            add(sources, "DOCUMENT", document.title(), document.evidence());
            // A document evidence date does not prove its issuance address was reviewed on that date.
            sources.add(new Source("ISSUANCE", document.title() + " · " + document.issuer(), document.issueUrl(), null));
        });
        content.schedules().forEach(schedule -> add(sources, "SCHEDULE", schedule.title(), schedule.evidence()));
        return new Snapshot(notice.revision, notice.version, List.copyOf(sources));
    }

    private void add(List<Source> sources, String kind, Evidence evidence) {
        if (evidence != null) sources.add(new Source(kind, evidence.label(), evidence.url(), evidence.checkedAt()));
    }

    private void add(List<Source> sources, String kind, String context, Evidence evidence) {
        if (evidence != null)
            sources.add(new Source(kind, context + " · " + evidence.label(), evidence.url(), evidence.checkedAt()));
    }
}
