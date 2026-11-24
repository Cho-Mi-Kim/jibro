package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;

import java.time.*;
import java.util.*;

import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class NoticeService {
    private final NoticeRepository repo;
    private final NoticeQueryRepository queries;
    private final ObjectMapper json;
    private final NoticeAuditRepository audit;

    public NoticeService(NoticeRepository repo, NoticeQueryRepository queries, ObjectMapper json, NoticeAuditRepository audit) {
        this.repo = repo;
        this.queries = queries;
        this.json = json;
        this.audit = audit;
    }

    public Content content(UUID id) {
        return json.readValue(entity(id).content, Content.class);
    }

    public boolean sourceExists(String key) {
        return repo.findBySourceKey(key).isPresent();
    }

    public boolean applicationClosed(UUID id) {
        var n = entity(id);
        return AutomaticReviewEligibility.closed(n, json.readValue(n.content, Content.class), Instant.now());
    }

    @Transactional
    public void lock(UUID id) {
        repo.locked(id).orElseThrow(ApiException::notFound);
    }

    public NoticeEntity entity(UUID id) {
        return repo.findById(id).orElseThrow(ApiException::notFound);
    }

    public Notice notice(UUID id) {
        return view(publishedEntity(id), false);
    }

    NoticeEntity publishedEntity(UUID id) {
        var n = entity(id);
        if (!n.basicReview.equals("PASS") || n.serviceExcluded || sample(n)) throw ApiException.notFound();
        return n;
    }

    public Notice view(NoticeEntity n, boolean admin) {
        return view(n, admin, Instant.now());
    }

    Notice view(NoticeEntity n, boolean admin, Instant now) {
        Content c = json.readValue(n.content, Content.class);
        String state = guideState(n.changeReview, n.guideReview);
        var timing = new ApplicationPeriod.Window(n.applicationStartAt, n.applicationEndAt);
        String status = c.applicationClosure() == null && !n.sourceClosed && !n.serviceClosed
                ? ApplicationPeriod.status(n.startDate, n.endDate, timing, now) : "CLOSED";
        boolean ready = n.basicReview.equals("PASS") && state.equals("AVAILABLE");
        boolean normal = n.lifecycleState.equals("NORMAL");
        boolean showSummaries = ready && !admin && !n.serviceExcluded
                && (normal || n.lifecycleState.equals("EARLY_CLOSED"))
                && (normal && !AutomaticReviewEligibility.closed(n, c, now) || reviewedBeforeDeadline(n, c, now));
        String summary = admin || status.equals("CLOSED") && !showSummaries ? c.summary()
                : NoticeSummary.display(n.title, n.agency, c.summary());
        var live = !admin && ("OPEN".equals(status) || "UPCOMING".equals(status)) && "NORMAL".equals(n.lifecycleState)
                && n.sourceKey != null && n.sourceKey.startsWith("LH:")
                ? repo.officialFiles(n.id, n.sourceFingerprint).map(value -> json.readValue(value, OfficialFileManifest.class)).orElse(null) : null;
        var attachments = live != null ? live.attachments(c.attachments()) : admin ? c.attachments() : ReviewedNoticeFiles.attachments(n.id.toString(), n.version, c.attachments());
        var documents = ready && !admin ? ReviewedNoticeFiles.documents(n.id.toString(), n.version, c.documents(), attachments, live)
                : admin ? c.documents() : List.<DocumentTemplate>of();
        if (live != null && ready) documents = live.documents(documents);
        String region = admin || n.sourceFingerprint == null ? n.region : SupplyRegionNames.display(n.region,
                queries.verifiedRegions(List.of(n.id)).getOrDefault(n.id, List.of()));
        return new Notice(n.id.toString(), n.title, n.agency, region, n.supportType, status, n.startDate, n.endDate, n.publishedDate,
                attachments, summary, n.version, ready ? n.version : null, state, ready && !status.equals("CLOSED") && n.lifecycleState.equals("NORMAL"), c.evidence(),
                ready || admin ? c.questions() : List.of(), ready || admin ? c.documents().stream().map(DocumentTemplate::title).toList() : List.of(), c.schedules(),
                n.basicCheckedAt, n.guideCheckedAt, n.collectedAt,
                new NoticeLifecycle(n.lifecycleState, n.lifecycleReason,
                        n.lifecycleEvidence == null ? null : json.readValue(n.lifecycleEvidence, Evidence.class),
                        n.lifecycleCheckedAt, n.lifecycleRevision),
                new ApplicationTiming(timing.startsAt(), timing.endsAt(),
                        c.applicationClosure() == null && !n.sourceClosed && !n.serviceClosed ? ApplicationPeriod.nextChange(n.startDate, n.endDate, timing, now) : null,
                        c.schedules().stream().anyMatch(s -> ApplicationTiming.UNTIL_FILLED_SESSION.equals(s.title()))),
                ready || admin ? c.reviewIssues() : List.of(), documents,
                showSummaries ? NoticeQualificationSummary.from(summary, c.questions()) : null,
                showSummaries ? NoticeDocumentSummary.from(c) : null);
    }

    private boolean reviewedBeforeDeadline(NoticeEntity n, Content c, Instant now) {
        Instant deadline = AutomaticReviewEligibility.deadline(n.endDate, n.applicationEndAt);
        Instant cutoff = n.intakeClosedAt;
        if (cutoff == null && (n.serviceClosed || !n.lifecycleState.equals("NORMAL") || c.applicationClosure() != null))
            return false;
        if (deadline != null && !deadline.isAfter(now) && (cutoff == null || deadline.isBefore(cutoff)))
            cutoff = deadline;
        if (cutoff != null && n.guideCheckedAt != null && n.guideCheckedAt.isBefore(cutoff)) return true;
        if (c.evidence() == null || c.evidence().url() == null || c.evidence().url().isBlank()) return false;
        if (cutoff != null) return audit.hadPublishedGuidance(n.id, c.evidence().url(), cutoff);
        // Legacy LH closures lack a transition timestamp; the archived list can prove intake was still open.
        return n.sourceClosed && n.guideCheckedAt != null && n.guideCheckedAt.isBefore(now)
                && audit.listedAsAcceptingAfter(c.evidence().url(), n.guideCheckedAt, now);
    }

    public Page<NoticeListItem> search(List<String> regions, List<String> types, List<String> statuses, String keyword, String sort, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100 || keyword.length() > 200 || !List.of("active", "deadline", "newest").contains(sort)
                || !List.of("OPEN", "UPCOMING", "CLOSED", "UNKNOWN", "EARLY_CLOSED", "CANCELLED").containsAll(statuses))
            throw new IllegalArgumentException();
        Instant now = Instant.now();
        var result = queries.search(regions, types, statuses, keyword, sort, page, size, now);
        return new Page<>(listItems(result.items(), now), result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    public List<NoticeListItem> listByIds(List<UUID> orderedIds) {
        return listItems(queries.publicItems(orderedIds), Instant.now());
    }

    private List<NoticeListItem> listItems(List<NoticeListSource> items, Instant now) {
        var verified = queries.verifiedRegions(items.stream().map(NoticeListSource::id).toList());
        return items.stream().map(value -> listItem(value, now,
                SupplyRegionNames.display(value.region(), verified.getOrDefault(value.id(), List.of())))).toList();
    }

    private static String guideState(boolean changed, String review) {
        return changed ? "CHANGE_REVIEW" : review.equals("PASS") ? "AVAILABLE" : review.equals("UNSUPPORTED") ? "UNSUPPORTED" : "REVIEWING";
    }

    private NoticeListItem listItem(NoticeListSource n, Instant now, String region) {
        var timing = new ApplicationPeriod.Window(n.startsAt(), n.endsAt());
        String state = guideState(n.changeReview(), n.guideReview());
        return new NoticeListItem(n.id().toString(), n.title(), n.agency(), region, n.type(),
                n.officiallyClosed() ? "CLOSED" : ApplicationPeriod.status(n.startDate(), n.endDate(), timing, now),
                n.startDate(), n.endDate(), n.publishedDate(), state, state.equals("AVAILABLE") && n.partialGuidance(), n.collectedAt(),
                new NoticeLifecycle(n.lifecycleState(), n.lifecycleReason(),
                        n.lifecycleEvidence() == null ? null : json.readValue(n.lifecycleEvidence(), Evidence.class),
                        n.lifecycleCheckedAt(), n.lifecycleRevision()),
                new ApplicationTiming(n.startsAt(), n.endsAt(), n.officiallyClosed() ? null
                        : ApplicationPeriod.nextChange(n.startDate(), n.endDate(), timing, now), n.untilFilled()));
    }

    public Map<String, List<String>> filters() {
        return queries.filters();
    }

    public List<Review> reviews() {
        return repo.findAll().stream().filter(n -> !sample(n)).sorted(Comparator.comparing(n -> n.title)).map(this::reviewView).toList();
    }

    private boolean sample(NoticeEntity notice) {
        return notice.sourceKey != null && notice.sourceKey.startsWith("local-sample-");
    }

    private Review reviewView(NoticeEntity n) {
        return new Review(n.id.toString(), view(n, true), n.basicReview, n.guideReview, n.reason, n.revision);
    }

    @Transactional
    public Review review(UUID id, String actor, int revision, String basic, String guide, String reason) {
        var n = repo.locked(id).orElseThrow(ApiException::notFound);
        if (n.revision != revision) throw ApiException.conflict();
        if (!List.of("PASS", "HOLD").contains(basic) || !List.of("PASS", "HOLD", "UNSUPPORTED").contains(guide)
                || reason.isBlank() || reason.length() > 2000) throw new IllegalArgumentException();
        Content c = json.readValue(n.content, Content.class);
        if (guide.equals("PASS") && (c.questions().isEmpty() || c.documents().isEmpty() || c.schedules().isEmpty() || n.startDate == null || n.endDate == null))
            throw new ApiException(409, "GUIDE_INCOMPLETE", "질문·서류·일정·출처와 신청 기간을 작성한 후 준비 안내를 통과시켜 주세요.");
        n.basicReview = basic;
        n.automaticReviewBlocked = true;
        n.guideReview = guide;
        n.basicCheckedAt = basic.equals("PASS") ? Instant.now() : null;
        n.guideCheckedAt = guide.equals("PASS") ? Instant.now() : null;
        n.reason = reason;
        n.revision++;
        if (guide.equals("PASS") || guide.equals("UNSUPPORTED")) n.changeReview = false;
        audit.recordReview(id, UUID.fromString(actor), n.revision, basic, guide, reason);
        return reviewView(n);
    }

    @Transactional
    public UUID insert(String key, String title, String agency, String region, String type, LocalDate start, LocalDate end, LocalDate published, Content c) {
        if (repo.findBySourceKey(key).isPresent()) return repo.findBySourceKey(key).orElseThrow().id;
        var n = new NoticeEntity();
        n.id = UUID.randomUUID();
        n.sourceKey = key;
        n.title = title;
        n.agency = agency;
        n.region = region;
        n.supportType = type;
        n.startDate = start;
        n.endDate = end;
        n.publishedDate = published;
        n.content = json.writeValueAsString(c);
        updateTiming(n, c);
        repo.save(n);
        return n.id;
    }

    @Transactional
    public void samplePublish(UUID id, String guide) {
        var n = entity(id);
        n.basicReview = "PASS";
        n.guideReview = guide;
    }

    @Transactional
    public UUID importSource(org.jibro.collection.LhNoticeClient.Item item) {
        var existing = repo.findBySourceKey(item.key());
        if (existing.isEmpty()) {
            UUID id = insert(item.key(), item.title(), "LH", item.region(), item.type(), null, null, item.published(),
                    new Content("공식 목록에서 자동 수집했습니다. 신청 기간·조건·서류는 원문 대조 후 제공합니다.",
                            new Evidence("LH 공식 공고 원문", item.url(), LocalDate.now().toString()), List.of(), List.of(), List.of(), List.of()));
            entity(id).sourceFingerprint = item.fingerprint();
            entity(id).sourceClosed = item.officiallyClosed();
            entity(id).collectedAt = Instant.now();
            excludeNonResidential(entity(id), item.title());
            return id;
        }
        var n = repo.locked(existing.get().id).orElseThrow();
        excludeNonResidential(n, item.title());
        n.sourceClosed = item.officiallyClosed();
        if (canBindReviewedSource(n, item)) {
            // Establish the first catalogue baseline without treating broader API labels as an edit
            // to already published guidance. No new review or review timestamp is created here.
            n.sourceFingerprint = item.fingerprint();
            n.collectedAt = Instant.now();
            return n.id;
        }
        n.collectedAt = Instant.now();
        if (item.onlyClosedSince(n.sourceFingerprint)) {
            n.sourceFingerprint = item.fingerprint();
            return n.id;
        }
        if (!Objects.equals(n.sourceFingerprint, item.fingerprint())) {
            n.title = item.title();
            n.region = item.region();
            n.supportType = item.type();
            if (item.published() != null) n.publishedDate = item.published();
            requireReview(n);
            n.sourceFingerprint = item.fingerprint();
        }
        return n.id;
    }

    private void excludeNonResidential(NoticeEntity n, String title) {
        String reason = NonResidentialNoticeScope.exclusionReason(title);
        if (n.serviceExcluded || reason == null) return;
        n.serviceExcluded = true;
        n.automaticReviewBlocked = true;
        n.serviceDecisionReason = reason;
        n.serviceDecidedAt = Instant.now();
        n.revision++;
        repo.flush();
        audit.recordServiceDecision(n.id, n.revision, "EXCLUDE", "COLLECTION_SCOPE", reason);
    }

    private boolean canBindReviewedSource(NoticeEntity n, org.jibro.collection.LhNoticeClient.Item item) {
        if (n.sourceFingerprint != null || n.collectedAt != null || !n.automaticReviewBlocked || n.changeReview
                || !"NORMAL".equals(n.lifecycleState) || !"PASS".equals(n.basicReview)
                || !Set.of("PASS", "UNSUPPORTED").contains(n.guideReview) || !"LH".equals(n.agency)
                || item.fingerprint() == null || !item.fingerprint().matches("[0-9a-f]{64}")
                || item.url() == null || item.key() == null || !Objects.equals(n.sourceKey, item.key())
                || !item.key().equals(org.jibro.collection.LhNoticeClient.sourceKey(item.url()))
                || !text(n.title, 300) || !Objects.equals(n.title, item.title()) || n.publishedDate == null
                || !n.publishedDate.equals(item.published())) return false;
        try {
            var uri = java.net.URI.create(item.url());
            if (!"https".equals(uri.getScheme()) || !"apply.lh.or.kr".equals(uri.getHost())) return false;
            Content c = json.readValue(n.content, Content.class);
            validateContent(c);
            if (!item.url().equals(c.evidence().url())) return false;
            if (n.guideReview.equals("PASS") && (c.questions().isEmpty() || c.documents().isEmpty()
                    || c.schedules().isEmpty() || n.startDate == null || n.endDate == null
                    || n.startDate.isAfter(n.endDate))) return false;
            // A legacy review need not contain both boundaries; any boundary it does contain must agree.
            if (c.schedules().stream().anyMatch(s ->
                    ApplicationPeriod.basicTitle(s.title(), false) && !Objects.equals(n.startDate, ApplicationPeriod.day(s.date()))
                            || ApplicationPeriod.basicTitle(s.title(), true) && !Objects.equals(n.endDate, ApplicationPeriod.day(s.date()))))
                return false;
        } catch (RuntimeException invalidContent) {
            return false;
        }
        return audit.permitsInitialSourceBinding(n.id, n.revision, n.guideReview);
    }

    @Transactional
    public Review updateContent(UUID id, int revision, Content c) {
        validateContent(c);
        var n = repo.locked(id).orElseThrow(ApiException::notFound);
        if (n.revision != revision) throw ApiException.conflict();
        n.content = json.writeValueAsString(c);
        updateTiming(n, c);
        requireReview(n);
        n.automaticReviewBlocked = true;
        return reviewView(n);
    }

    public record AttachmentSnapshot(int revision, Content content) {
    }

    @Transactional
    public AttachmentSnapshot attachmentSnapshot(UUID id) {
        var n = repo.locked(id).orElseThrow(ApiException::notFound);
        return new AttachmentSnapshot(n.revision, json.readValue(n.content, Content.class));
    }

    @Transactional
    public void attachmentChanged(UUID id, int revision, String reason) {
        var n = repo.locked(id).orElseThrow(ApiException::notFound);
        if (n.revision != revision) throw ApiException.conflict();
        requireReview(n);
        n.reason = reason;
        repo.saveAndFlush(n); // The extraction queue reads the new revision through JDBC in this transaction.
    }

    private void requireReview(NoticeEntity n) {
        n.version++;
        n.revision++;
        n.basicReview = "HOLD";
        n.guideReview = "HOLD";
        n.changeReview = true;
        n.basicCheckedAt = null;
        n.guideCheckedAt = null;
    }

    public record Draft(String title, String agency, String region, String type, LocalDate startDate, LocalDate endDate,
                        LocalDate publishedDate, Content content) {
    }

    public Draft draft(UUID id) {
        var n = entity(id);
        return new Draft(n.title, n.agency, n.region, n.supportType, n.startDate, n.endDate, n.publishedDate, content(id));
    }

    @Transactional
    public Review updateDraft(UUID id, int revision, Draft draft) {
        if (draft == null || !text(draft.title(), 300) || !text(draft.agency(), 100) || !text(draft.region(), 100) || !text(draft.type(), 100)
                || draft.startDate() != null && draft.endDate() != null && draft.startDate().isAfter(draft.endDate()))
            throw new IllegalArgumentException();
        updateContent(id, revision, draft.content());
        var n = entity(id);
        n.title = draft.title();
        n.agency = draft.agency();
        n.region = draft.region();
        n.supportType = draft.type();
        n.startDate = draft.startDate();
        n.endDate = draft.endDate();
        n.publishedDate = draft.publishedDate();
        updateTiming(n, draft.content());
        return reviewView(n);
    }

    private void updateTiming(NoticeEntity n, Content content) {
        var timing = ApplicationPeriod.resolve(n.startDate, n.endDate, content.schedules());
        n.applicationStartAt = timing.startsAt();
        n.applicationEndAt = timing.endsAt();
    }

    public void validateContent(Content c) {
        if (c == null || c.summary() == null || c.summary().length() > 10000 || c.evidence() == null || c.questions() == null
                || c.documents() == null || c.schedules() == null || c.attachments() == null || c.questions().size() > Content.MAX_QUESTIONS
                || c.documents().size() > 100 || c.schedules().size() > 100 || c.attachments().size() > 50)
            throw new IllegalArgumentException();
        validateEvidence(c.evidence());
        c.attachments().forEach(this::validateEvidence);
        if (c.applicationClosure() != null) {
            var closure = c.applicationClosure();
            if (!text(closure.reason(), 500) || !c.summary().contains(closure.reason())
                    || closure.evidence() == null || !text(closure.evidence().url(), 2000))
                throw new IllegalArgumentException("공식 접수 마감 상태의 설명과 출처를 확인해 주세요.");
            validateEvidence(closure.evidence());
        }
        Set<String> questions = new HashSet<>();
        for (var q : c.questions()) {
            if (q == null || !validId(q.id()) || !questions.add(q.id()) || !text(q.title(), 300) || !text(q.reason(), 2000))
                throw new IllegalArgumentException();
            validateEvidence(q.evidence());
            QuestionAnswers.validateQuestion(q);
        }
        Set<String> docs = new HashSet<>();
        for (var d : c.documents()) {
            if (d == null || !validId(d.id()) || !docs.add(d.id()) || !text(d.title(), 300) || !text(d.method(), 2000) || !text(d.issuer(), 100)
                    || d.conditionQuestionId() != null && !questions.contains(d.conditionQuestionId()))
                throw new IllegalArgumentException();
            validateUrl(d.issueUrl());
            if (d.conditionQuestionId() != null && c.questions().stream().filter(q -> q.id().equals(d.conditionQuestionId()))
                    .anyMatch(q -> !Set.of("SINGLE", "MULTIPLE").contains(q.inputKind())))
                throw new IllegalArgumentException();
            if (d.conditionAnswerValues() != null) {
                if (d.conditionQuestionId() == null || d.conditionAnswerValues().isEmpty()
                        || new HashSet<>(d.conditionAnswerValues()).size() != d.conditionAnswerValues().size())
                    throw new IllegalArgumentException();
                var question = c.questions().stream().filter(q -> q.id().equals(d.conditionQuestionId())).findFirst().orElseThrow();
                var values = question.answerOptions().stream().map(AnswerOption::value).toList();
                if (d.conditionAnswerValues().contains("UNKNOWN") || !values.containsAll(d.conditionAnswerValues()))
                    throw new IllegalArgumentException();
            } else if (d.conditionQuestionId() != null && c.questions().stream().filter(q -> q.id().equals(d.conditionQuestionId()))
                    .flatMap(q -> q.answerOptions().stream()).noneMatch(o -> o.value().equals("YES")))
                throw new IllegalArgumentException();
            validateEvidence(d.evidence());
        }
        Set<String> schedules = new HashSet<>();
        for (var s : c.schedules()) {
            if (s == null || !validId(s.id()) || !schedules.add(s.id()) || !text(s.title(), 300)
                    || !validScheduleTiming(s))
                throw new IllegalArgumentException();
            validateEvidence(s.evidence());
        }
        AnswerDependencies.validate(c);
        GuidanceIssues.validate(c);
    }

    private boolean validScheduleTiming(ScheduleTemplate schedule) {
        boolean dated = text(schedule.date(), 40) && ApplicationPeriod.day(schedule.date()) != null;
        if (schedule.timingDescription() == null) return dated;
        if (dated) return text(schedule.timingDescription(), 1000);
        if (ApplicationTiming.VISIT_CONTACT.equals(schedule.title()) || ApplicationTiming.UNTIL_FILLED_SESSION.equals(schedule.title()))
            return text(schedule.timingDescription(), 1000) && ("".equals(schedule.date())
                    || text(schedule.date(), 40) && ApplicationPeriod.day(schedule.date()) != null);
        return "".equals(schedule.date()) && text(schedule.timingDescription(), 1000)
                && (schedule.title().equals("접수 회차 · 상시 접수")
                || schedule.title().equals("접수 회차 · 온라인 접수일 확인 필요")
                || schedule.title().equals(ApplicationTiming.VISIT_NOTE)
                || schedule.title().equals(ApplicationTiming.UNDATED_HOURS_SESSION)
                || schedule.title().matches("(?:결과|당첨자|입주대상자) 발표(?: · 개별 안내)?"));
    }

    private boolean validId(String s) {
        return s != null && s.matches("[a-zA-Z0-9_-]{1,64}");
    }

    private boolean text(String s, int length) {
        return s != null && !s.isBlank() && s.length() <= length;
    }

    private void validateEvidence(Evidence e) {
        EvidenceValidation.validate(e);
    }

    private void validateUrl(String url) {
        EvidenceValidation.validateUrl(url);
    }
}
