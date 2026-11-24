import type {
    DocumentSummaryGroup,
    Evidence,
    Notice,
    Preparation,
    Schedule,
} from '../housing/types';
import type { NoticeComparison } from './comparisonApi';
import {
    reviewedPreparationScope,
    visiblePreparationDocuments,
    visiblePreparationQuestions,
} from '../housing/preparationApplicability';
import {
    currentPreparationScheduleSource,
    dateWithScheduleTime,
    isBasicScheduleAlias,
    individualAnnouncement,
    scheduleDay,
    scheduleMilestone,
} from '../housing/schedules';

export type ComparisonSlot = 'a' | 'b';
export const comparisonSlots = ['a', 'b'] as const;

/** The caller supplies a current, same-notice preparation with saved answers. */
export function personalizeComparison(
    data: NoticeComparison,
    preparation: Preparation,
): NoticeComparison {
    const notice = data.notice;
    const scope = reviewedPreparationScope(preparation, {
        ...notice,
        documentTemplates: [
            ...(notice.documentTemplates ?? []),
            ...(data.documentRequirements ?? []),
        ],
    });
    const savedQuestionIds = new Set(scope.questions.map((q) => q.id));
    const visibleQuestionIds = new Set(
        visiblePreparationQuestions(scope).map((q) => q.id),
    );
    const documentMetadata = [
        ...(notice.documentTemplates ?? []),
        ...(data.documentRequirements ?? []),
        ...(notice.documentSummary?.common.items ?? []),
        ...(notice.documentSummary?.conditional.items ?? []),
    ];
    const publicDocuments = new Map(
        documentMetadata.map((document) => [document.id, document]),
    );
    const visibleDocumentIds = new Set(
        visiblePreparationDocuments(scope).map((document) => document.id),
    );
    const excludedDocumentIds = new Set(
        preparation.documents
            .filter((document) => !visibleDocumentIds.has(document.id))
            .map((document) => document.id),
    );
    const excludedTitles = new Set(
        documentMetadata
            .filter((document) => excludedDocumentIds.has(document.id))
            .map((document) => document.title),
    );
    const retainedTitles = new Set(
        documentMetadata
            .filter((document) => !excludedDocumentIds.has(document.id))
            .map((document) => document.title),
    );
    const summary = notice.documentSummary;
    return {
        ...data,
        documentRequirements:
            data.documentRequirements?.filter(
                (document) => !excludedDocumentIds.has(document.id),
            ) ?? null,
        notice: {
            ...currentPreparationScheduleSource(scope, notice),
            questions: notice.questions.filter(
                (question) =>
                    !savedQuestionIds.has(question.id) ||
                    visibleQuestionIds.has(question.id),
            ),
            documents: notice.documents.filter(
                (title) =>
                    !excludedTitles.has(title) || retainedTitles.has(title),
            ),
            documentTemplates: notice.documentTemplates?.filter(
                (document) => !excludedDocumentIds.has(document.id),
            ),
            documentSummary: summary
                ? {
                      ...summary,
                      common: personalDocumentGroup(
                          summary.common,
                          excludedDocumentIds,
                          publicDocuments,
                      ),
                      conditional: personalDocumentGroup(
                          summary.conditional,
                          excludedDocumentIds,
                          publicDocuments,
                      ),
                  }
                : summary,
        },
    };
}

function personalDocumentGroup(
    group: DocumentSummaryGroup,
    excluded: ReadonlySet<string>,
    documents: ReadonlyMap<string, DocumentSummaryGroup['items'][number]>,
): DocumentSummaryGroup {
    const items = group.items.filter((item) => !excluded.has(item.id));
    const remainingIds = group.remainingIds?.filter((id) => !excluded.has(id));
    const promoted = (remainingIds ?? [])
        .flatMap((id) => documents.get(id) ?? [])
        .slice(0, Math.max(1, group.items.length) - items.length)
        .map(({ id, title, evidence }) => ({ id, title, evidence }));
    const promotedIds = new Set(promoted.map((item) => item.id));
    const removedRemainingCount =
        (group.remainingIds?.length ?? 0) - (remainingIds?.length ?? 0);
    return {
        ...group,
        items: [...items, ...promoted],
        remainingIds: remainingIds?.filter((id) => !promotedIds.has(id)),
        // An omitted remaining-id list does not prove which unknown entries to remove.
        remainingCount: Math.max(
            0,
            group.remainingCount - removedRemainingCount - promoted.length,
        ),
    };
}

export function selectedNotices(params: URLSearchParams) {
    const first = params.get('a') || undefined;
    const second = params.get('b') || undefined;
    return { a: first, b: second === first ? undefined : second };
}

export function selectComparisonNotice(
    params: URLSearchParams,
    slot: ComparisonSlot,
    id?: string,
) {
    const next = new URLSearchParams(params);
    const other = slot === 'a' ? 'b' : 'a';
    if (id && id !== next.get(other)) next.set(slot, id);
    else next.delete(slot);
    return next;
}

interface ComparisonSchedule {
    id: string;
    title: string;
    date: string | null;
    evidence?: Evidence;
    timingDescription?: string | null;
}

export function comparisonSchedules(notice: Notice): ComparisonSchedule[] {
    const events = notice.schedules ?? [];
    const rows = [
        ['published', '공고일', notice.publishedDate],
        ['start', '접수 시작', notice.startDate],
        ['end', '접수 마감', notice.endDate],
        ['result', '결과 발표', null],
        ['documents', '서류 마감', null],
    ] as const;
    const represented = new Set<Omit<Schedule, 'recorded'>>();
    const result = rows.flatMap(
        ([kind, title, basicDate]): ComparisonSchedule[] => {
            const basic =
                basicDate && scheduleDay(basicDate) ? basicDate : null;
            const milestone = scheduleMilestone(notice, kind);
            if (milestone.alternatives.length)
                return milestone.alternatives.map((event) => {
                    represented.add(event);
                    return {
                        ...event,
                        id: 'detail:' + event.id,
                        date: dateWithScheduleTime(event.date, event),
                    };
                });
            const { date } = milestone;
            const event =
                milestone.event ??
                (kind === 'result'
                    ? individualAnnouncement(events)
                    : undefined);
            if (event) {
                represented.add(event);
                if (kind === 'start' || kind === 'end')
                    events
                        .filter((item) =>
                            isBasicScheduleAlias(item, event, kind),
                        )
                        .forEach((item) => represented.add(item));
            }
            return [
                {
                    id: kind,
                    title: event?.title ?? title,
                    date,
                    timingDescription: event?.timingDescription,
                    evidence:
                        event?.evidence ??
                        (basic ? notice.evidence : undefined),
                },
            ];
        },
    );
    // Keep additional cohorts, stages, and detailed deadlines from the public source.
    return result.concat(
        events
            .filter((event) => !represented.has(event))
            .map((event) => ({
                ...event,
                id: 'detail:' + event.id,
                date: scheduleDay(event.date)
                    ? dateWithScheduleTime(event.date, event)
                    : null,
            })),
    );
}
