package org.jibro.collection;

import static org.jibro.notice.HousingModels.*;

import java.time.*;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.UUID;

import org.jibro.collection.attachments.AttachmentCheckService;
import org.jibro.collection.attachments.AttachmentCheckStore;
import org.jibro.notice.NoticeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CollectionService {
    public record ScheduleStatus(boolean enabled, boolean configured, String timezone, List<String> times,
                                 String nextRunAt, String providerResumeAt, int pendingRuns, String message) {
    }

    private final AttachmentCheckStore attachmentStore;
    private final LhNoticeClient client;
    private final NoticeService notices;
    private final AttachmentCheckService attachments;
    private final CollectionRunStore runs;
    private final CollectionResponseService responses;
    private final List<CollectionItemProcessor> processors;
    private final int maxPages;
    private final boolean scheduleEnabled;

    public CollectionService(AttachmentCheckStore attachmentStore, LhNoticeClient client, NoticeService notices, AttachmentCheckService attachments,
                             CollectionRunStore runs, CollectionResponseService responses, List<CollectionItemProcessor> processors,
                             @Value("${app.collection.max-pages:5}") int maxPages,
                             @Value("${app.collection.schedule-enabled:true}") boolean scheduleEnabled) {
        this.attachmentStore = attachmentStore;
        this.client = client;
        this.notices = notices;
        this.attachments = attachments;
        this.runs = runs;
        this.responses = responses;
        this.processors = processors;
        this.maxPages = Math.max(1, Math.min(20, maxPages));
        this.scheduleEnabled = scheduleEnabled;
    }

    public List<Collection> list() {
        return runs.list();
    }

    public ScheduleStatus schedule() {
        Instant now = Instant.now();
        Instant providerResumeAt = runs.providerResumeAt(now);
        return new ScheduleStatus(scheduleEnabled, client.configured(), CollectionSchedule.ZONE.getId(),
            CollectionSchedule.HOURS.stream().map(h -> String.format("%02d:00", h)).toList(),
            scheduleEnabled && client.configured() ? CollectionSchedule.next(now).toString() : null,
            providerResumeAt == null ? null : providerResumeAt.toString(),
            runs.pending(), !scheduleEnabled ? "예약 수집이 꺼져 있습니다. 수동 수집은 사용할 수 있습니다."
            : !client.configured() ? "LH 수집 키가 없어 예약 수집을 실행하지 않습니다."
            : "한국시간 00시·06시·09~18시 정각에 수집합니다. 서버가 실행 중일 때 동작하며 재시작 시 최근 누락 일정 하나를 확인합니다.");
    }

    public Collection collect(String actor, int startPage) {
        UUID id = runs.enqueueManual(actor, startPage, Instant.now());
        var work = runs.claim(id, Instant.now());
        if (work != null) execute(work);
        return runs.view(id);
    }

    public void enqueueLatest(Instant now) {
        if (scheduleEnabled && client.configured()) runs.enqueueScheduled(CollectionSchedule.latest(now), now);
    }

    public void processDue() {
        var work = runs.claim(null, Instant.now());
        if (work != null) execute(work);
    }

    private void execute(CollectionRunStore.Work work) {
        if (!client.configured()) {
            runs.complete(work, "NOT_CONFIGURED", "LH_SERVICE_KEY가 없어 외부 수집을 실행하지 않았습니다.", Instant.now());
            return;
        }
        int count = work.processed(), processorFailures = 0, pageLimitIssues = 0;
        int nextPage = work.page(), scopeIndex = work.scopeIndex();
        var imported = new LinkedHashSet<UUID>();
        Exception sourceFailure = null;
        try {
            for (int requested = 0; requested < maxPages && scopeIndex < work.categories().size(); requested++) {
                int page = nextPage;
                int completedScope = scopeIndex;
                runs.heartbeat(work, Instant.now());
                var batch = fetchPage(work, scopeIndex, page);
                for (var item : batch.items()) {
                    UUID noticeId = runs.withinLease(work, Instant.now(), () -> notices.importSource(item));
                    imported.add(noticeId);
                    count++;
                    for (var processor : processors) {
                        runs.heartbeat(work, Instant.now());
                        try {
                            processor.process(noticeId, item);
                        } catch (RuntimeException ex) {
                            processorFailures++;
                        }
                    }
                }
                boolean more = CollectionPage.hasMore(batch, page);
                if (more && page < CollectionQueryPlan.MAX_PAGE) {
                    nextPage = page + 1;
                } else {
                    if (more) pageLimitIssues++;
                    scopeIndex++;
                    nextPage = 1;
                }
                runs.checkpoint(work, new CollectionRunStore.PageProgress(completedScope, page, CollectionPage.signature(batch),
                    scopeIndex, nextPage, count, more && page == CollectionQueryPlan.MAX_PAGE), Instant.now());
            }
        } catch (CollectionRunStore.LeaseLost stopped) {
            return;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            sourceFailure = ex;
        }
        try {
            var budget = new AttachmentCheckService.Budget();
            int checked = 0, incomplete = 0;
            var targets = imported.stream().filter(noticeId -> !notices.content(noticeId).attachments().isEmpty())
                .sorted(Comparator.comparing(this::lastAttachmentAttempt)).toList();
            for (UUID noticeId : targets) {
                runs.heartbeat(work, Instant.now());
                try {
                    var result = attachments.check(noticeId, work.actor(), budget);
                    checked++;
                    if (!result.status().equals("SUCCEEDED")) incomplete++;
                } catch (Exception ex) {
                    incomplete++;
                }
            }
            if (incomplete + processorFailures > 0)
                runs.recordIssues(work, incomplete + processorFailures, Instant.now());
            if (sourceFailure != null) {
                runs.failure(work, sourceFailure, Instant.now());
                return;
            }
            String message = count + "건 처리. 신규·변경 공고는 검수 대기입니다.";
            if (checked + incomplete > 0) message += " 첨부 점검 공고 " + checked + "건, 추가 확인 필요 " + incomplete + "건.";
            if (processorFailures > 0) message += " 수집 후속 처리 실패 " + processorFailures + "건. 목록 수집과 후속 검증 상태는 별개입니다.";
            if (pageLimitIssues > 0) message += " 일부 분류가 500페이지 안전 한도에 도달했습니다. 수집 범위를 확인해 주세요.";
            if (scopeIndex < work.categories().size()) {
                runs.continuePages(work, message + " 분류 " + work.categories().get(scopeIndex) + "의 " + nextPage + "페이지부터 이어서 수집합니다.", Instant.now());
                return;
            }
            int allIssues = work.issues() + incomplete + processorFailures + pageLimitIssues;
            runs.complete(work, allIssues > 0 ? "PARTIAL" : "SUCCEEDED", message
                + (allIssues > 0 ? " 이 수집의 추가 확인 항목 " + allIssues + "건." : ""), Instant.now());
        } catch (CollectionRunStore.LeaseLost stopped) {
            // A new worker recovered the expired lease. The old worker cannot overwrite its state.
        } catch (Exception ex) {
            runs.failure(work, ex, Instant.now());
        }
    }

    private Instant lastAttachmentAttempt(UUID id) {
        return attachmentStore.lastAttempt(id);
    }

    private LhNoticeClient.Batch fetchPage(CollectionRunStore.Work work, int scopeIndex, int page) throws Exception {
        var query = work.query(scopeIndex);
        UUID receipt = runs.withinLease(work, Instant.now(), () -> responses.begin(work, page, query));
        LhNoticeClient.Batch batch = null;
        try {
            batch = client.fetch(page, query);
            CollectionPage.validate(batch, page);
            runs.requireNewPage(work, scopeIndex, page, CollectionPage.signature(batch), Instant.now());
        } catch (Exception failure) {
            SourceResponse evidence = failure instanceof SourceResponse.Rejected rejected ? rejected.response() : batch == null ? null : batch.response();
            responses.complete(receipt, evidence, evidence == null && (failure instanceof java.io.IOException || failure instanceof InterruptedException) ? "TRANSPORT_FAILED" : "REJECTED");
            throw failure;
        }
        responses.complete(receipt, batch.response(), "ACCEPTED");
        return batch;
    }
}
