package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;

import java.util.*;

import org.jibro.global.common.ApiException;
import org.jibro.notice.NoticeService;
import org.jibro.notice.GuidanceIssues;
import org.jibro.notice.ReviewedNoticeFiles;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PreparationService {
    private final PreparationRepository repository;
    private final NoticeService notices;

    public PreparationService(PreparationRepository repository, NoticeService notices) {
        this.repository = repository;
        this.notices = notices;
    }

    @Transactional(readOnly = true)
    public List<PreparationListItem> list(String user) {
        return repository.findActive(UUID.fromString(user));
    }

    public Preparation get(String user, UUID id) {
        return decorate(load(user, id, false).preparation());
    }

    public Preparation start(String user, UUID noticeId, int version) {
        // Serialize starts for one owner before checking the unique owner/notice key.
        if (!repository.lockOwner(UUID.fromString(user)))
            throw ApiException.notFound();
        var existing = repository.findByNotice(UUID.fromString(user), noticeId);
        if (existing.isPresent()) {
            if (existing.get().archived())
                throw new ApiException(409, "ARCHIVED", "보관함에서 이 준비 건을 복원해 주세요.");
            return get(user, existing.get().id());
        }
        notices.lock(noticeId);
        Notice notice = notices.notice(noticeId);
        if (!notice.canStart() || notice.version() != version) throw ApiException.conflict();
        Content content = notices.content(noticeId);
        Snapshot snap = PreparationSnapshots.fresh(notice, content);
        Preparation p = snap.preparation();
        repository.insert(UUID.fromString(user), noticeId, version, snap);
        return p;
    }

    public Preparation refresh(String user, UUID id, int revision, int version) {
        Snapshot previous = load(user, id, true);
        Preparation old = previous.preparation();
        if (old.revision != revision) throw ApiException.conflict();
        UUID noticeId = UUID.fromString(old.noticeId);
        notices.lock(noticeId);
        Notice current = notices.notice(noticeId);
        if (!current.guideState().equals("AVAILABLE") || current.version() != version || old.version >= version)
            throw ApiException.conflict();
        Snapshot next = PreparationSnapshots.fresh(current, notices.content(noticeId));
        Preparation p = next.preparation();
        p.id = old.id;
        p.revision = old.revision;
        for (var question : p.questions) {
            // Only an identical question contract may retain an old answer.
            if (old.questions.contains(question) && old.answers.containsKey(question.id()))
                p.answers.put(question.id(), old.answers.get(question.id()));
        }
        for (var d : p.documents) {
            old.documents.stream().filter(prior -> prior.id.equals(d.id)).findFirst().ifPresent(prior -> {
                d.note = prior.note;
                d.state = prior.state == DocumentState.READY ? DocumentState.RECHECK : prior.state;
            });
            d.reviewRequired = GuidanceIssues.documentRequiresReview(p.reviewIssues, d.id, next.rules().get(d.id));
            d.necessity = d.reviewRequired ? "UNKNOWN" : PreparationRules.necessity(next.rules().get(d.id), next.valuesFor(d.id), p.answers);
        }
        for (var schedule : p.schedules)
            old.schedules.stream()
                .filter(prior -> prior.id.equals(schedule.id) && prior.date.equals(schedule.date) && prior.title.equals(schedule.title))
                .findFirst().ifPresent(prior -> schedule.recorded = prior.recorded);
        repository.retainHistory(id, old.version, previous);
        repository.updateNoticeVersion(id, version);
        return save(next);
    }

    public Preparation answers(String user, UUID id, int revision, Map<String, ?> answers) {
        Snapshot snap = editable(user, id, revision);
        PreparationSnapshots.applyAnswers(snap, answers);
        return save(snap);
    }

    public Preparation document(String user, UUID id, String documentId, int revision, DocumentState state, String note) {
        Snapshot snap = editable(user, id, revision);
        var d = snap.preparation().documents.stream().filter(v -> v.id.equals(documentId)).findFirst().orElseThrow(ApiException::notFound);
        if (state == null || note == null || note.length() > 2000) throw new IllegalArgumentException();
        if (d.reviewRequired || List.of("UNKNOWN", "NOT_APPLICABLE").contains(d.necessity))
            throw new ApiException(409, "CONDITION_REQUIRED", "서류 해당 여부를 먼저 확인하세요.");
        d.state = state;
        d.note = note;
        return save(snap);
    }

    public Preparation schedule(String user, UUID id, String scheduleId, int revision, boolean recorded) {
        Snapshot snap = editable(user, id, revision);
        var s = snap.preparation().schedules.stream().filter(v -> v.id.equals(scheduleId)).findFirst().orElseThrow(ApiException::notFound);
        s.recorded = recorded;
        return save(snap);
    }

    private Snapshot load(String user, UUID id, boolean lock) {
        return (lock ? repository.lockActive(UUID.fromString(user), id) : repository.findActive(UUID.fromString(user), id))
                .orElseThrow(ApiException::notFound);
    }

    private Snapshot editable(String user, UUID id, int revision) {
        Snapshot snap = load(user, id, true);
        notices.lock(UUID.fromString(snap.preparation().noticeId));
        Preparation p = decorate(snap.preparation());
        if (p.revision != revision || !p.guideState.equals("AVAILABLE")) throw ApiException.conflict();
        return snap;
    }

    private Preparation decorate(Preparation p) {
        Notice current = null;
        try {
            Notice n = notices.notice(UUID.fromString(p.noticeId));
            current = n;
            ReviewedNoticeFiles.applyToPreparation(p, n);
            p.guideState = n.version() != p.version ? "CHANGE_REVIEW" : n.guideState();
        } catch (ApiException ex) {
            if (ex.status != 404) throw ex;
            p.guideState = "CHANGE_REVIEW";
        }
        PreparationSnapshots.assess(p);
        if (current != null && current.lifecycle() != null && current.lifecycle().interrupted()) {
            String status = current.lifecycle().state().equals("CANCELLED") ? "공고 취소" : "신청 조기 마감";
            p.warnings = new ArrayList<>(p.warnings);
            p.warnings.add(status + " 안내가 확인되었습니다. 기존 준비 기록은 보존됩니다.");
            p.nextAction = new NextAction(status + " 안내 확인", current.lifecycle().reason(), "/notices/" + p.noticeId);
        }
        return p;
    }

    private Preparation save(Snapshot snap) {
        Preparation p = snap.preparation();
        p.revision++;
        PreparationSnapshots.assess(p);
        repository.save(snap);
        return decorate(p);
    }

}
