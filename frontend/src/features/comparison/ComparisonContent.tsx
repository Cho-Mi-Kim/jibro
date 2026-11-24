import { AreaReviewIssues } from '../housing/ReviewIssues';
import { ScheduleSummary, ScheduleCard } from '../housing/ScheduleCard';
import { noticeHousingFacts } from '../housing/noticeHousingFacts';
import { AgencyMark } from '../housing/AgencyMark';
import { noticeAgency } from '../housing/noticeAgency';
import { NoticeLifecycleNotice } from '../housing/NoticeLifecycleNotice';
import { useState, type ReactNode } from 'react';
import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';
import { useResultCardScroll } from '../../shared/ui/useResultCardScroll';
import { ConditionSummary } from '../housing/ConditionSummary';
import { noticeConditionQuestions } from '../housing/conditionOverview';
import {
    questionReviewIssues,
    documentReviewCountNote,
} from '../housing/reviewIssueSelectors';
import type { Evidence, Notice } from '../housing/types';
import { EvidenceLink } from '../housing/EvidenceLink';
import { noticeListTitle } from '../housing/noticeDisplay';
import { qualificationSummary } from '../housing/qualificationSummary';
import { QualificationSummaryDetails } from '../housing/QualificationSummaryDetails';
import { ConditionDetailText } from '../housing/ConditionDetailText';
import {
    noticeSupportSummaryRows,
    type SupportSummaryRow,
} from '../housing/supportSummaryRows';
import { ApplicationBadge, GuideBadge } from '../housing/NoticeStatusBadges';
import { guidanceStatus } from '../housing/noticeLabels';
import { CoreDocuments } from '../housing/CoreDocuments';
import { documentSummary, listedDocument } from '../housing/documentSummary';
import { DocumentCard } from '../housing/DocumentCard';
import type { NoticeComparison } from './comparisonApi';
import { comparisonSchedules, type ComparisonSlot } from './comparisonModel';
import s from './Comparison.module.css';

export interface ComparisonEntry {
    slot: ComparisonSlot;
    data?: NoticeComparison;
}

function Source({
    evidence,
    detail,
}: {
    evidence: Evidence;
    detail?: string | null;
}) {
    return (
        <details className={s.evidence}>
            <summary>
                {detail ? '세부 기준·공식 근거 보기' : '공식 근거·확인일'}
            </summary>
            {detail && <ConditionDetailText text={detail} />}
            <EvidenceLink evidence={evidence} />
        </details>
    );
}

function Section({
    title,
    entries,
    children,
    expandableColumn = false,
}: {
    title: string;
    entries: ComparisonEntry[];
    children: (data: NoticeComparison) => ReactNode;
    expandableColumn?: boolean;
}) {
    return (
        <section className={s.section} aria-label={title}>
            <h2>{title}</h2>
            <div className={s.pair}>
                {entries.map(({ slot, data }) => (
                    <div
                        className={s.column}
                        data-result-card={expandableColumn || undefined}
                        key={slot + ':' + (data?.notice.id ?? '')}
                        onClick={
                            data && expandableColumn
                                ? handleDisclosureCardClick
                                : undefined
                        }
                    >
                        <h3 title={data?.notice.title}>
                            <span className={s.slotNumber}>
                                {slot === 'a' ? '1' : '2'}
                            </span>
                            {data
                                ? noticeListTitle(data.notice.title)
                                : '공고 선택 필요'}
                        </h3>
                        {data ? (
                            children(data)
                        ) : (
                            <p className={s.muted}>
                                공고를 선택하고 불러오면 비교 항목을 표시해요.
                            </p>
                        )}
                    </div>
                ))}
            </div>
        </section>
    );
}

function GuidanceUnavailable({ notice }: { notice: Notice }) {
    return (
        <div
            className={s.item}
            data-result-card
            onClick={handleDisclosureCardClick}
        >
            <p>
                <strong>{guidanceStatus(notice).label}</strong>
            </p>
            <p className={s.muted}>
                검수된 조건·서류 안내가 공개되면 비교할 수 있어요. 해당 항목이
                없다는 뜻은 아니에요.
            </p>
            <Source evidence={notice.evidence} />
        </div>
    );
}

function Qualifications({ notice }: { notice: Notice }) {
    if (notice.guideState !== 'AVAILABLE')
        return <GuidanceUnavailable notice={notice} />;
    const summary = qualificationSummary(notice);
    return (
        <>
            <dl className={s.facts}>
                {summary.rows.map(({ label, text }) => (
                    <div key={label}>
                        <dt>{label}</dt>
                        <dd>{text}</dd>
                    </div>
                ))}
            </dl>
            <details className={s.evidence}>
                <summary>
                    {summary.tables
                        ? '배점표·공식 근거 보기'
                        : summary.detail || summary.reviewedDetails.length
                          ? '세부 기준·공식 근거 보기'
                          : '공식 근거·확인일'}
                </summary>
                <QualificationSummaryDetails summary={summary} />
                {!summary.evidence &&
                    !summary.scoreEvidence &&
                    !summary.reviewedDetails.length && (
                        <EvidenceLink evidence={notice.evidence} />
                    )}
            </details>
        </>
    );
}

function Conditions({ notice }: { notice: Notice }) {
    if (notice.guideState !== 'AVAILABLE')
        return <GuidanceUnavailable notice={notice} />;
    const questions = noticeConditionQuestions(notice.questions);
    if (!questions.length)
        return (
            <p className={s.muted}>
                표시할 조건 항목이 없습니다. 공식 공고를 확인해 주세요.
            </p>
        );
    return (
        <ul className={s.items}>
            {questions.map((question) => (
                <li
                    className={s.item}
                    key={question.id}
                    data-result-card
                    onClick={handleDisclosureCardClick}
                >
                    <ConditionSummary
                        question={question}
                        issues={questionReviewIssues(
                            notice.reviewIssues,
                            question.id,
                        )}
                        heading="h4"
                        showChoices
                    />
                </li>
            ))}
        </ul>
    );
}

function DocumentDisclosure({
    title,
    summary,
    evidence,
    method,
    kind,
}: {
    title: string;
    summary: ReturnType<typeof documentSummary>;
    evidence: Evidence;
    method?: string | null;
    kind?: string;
}) {
    return (
        <DocumentCard title={title} summary={summary} heading="h4">
            {kind && <span className={s.kind}>{kind}</span>}
            {summary.question && (
                <p className={s.muted}>관련 조건: {summary.question.title}</p>
            )}
            {!summary.fullMethodShown && method && (
                <ConditionDetailText text={method} />
            )}
            <EvidenceLink evidence={evidence} />
        </DocumentCard>
    );
}

function Documents({ notice, documentRequirements }: NoticeComparison) {
    const [expanded, setExpanded] = useState(false);
    if (notice.guideState !== 'AVAILABLE')
        return <GuidanceUnavailable notice={notice} />;
    const templates = documentRequirements ?? notice.documentTemplates;
    if (!templates || (documentRequirements === null && !templates.length))
        return (
            <>
                <p className={s.muted}>
                    현재 자료에는 서류명만 있습니다. 제출 구분·발급 기준은 공식
                    근거에서 확인해 주세요.
                </p>
                <ul>
                    {notice.documents.map((title) => {
                        const summary = documentSummary(
                            listedDocument(title, notice),
                            notice,
                        );
                        return (
                            <li
                                key={title}
                                data-result-card
                                onClick={handleDisclosureCardClick}
                            >
                                <DocumentDisclosure
                                    title={title}
                                    summary={summary}
                                    evidence={notice.evidence}
                                />
                            </li>
                        );
                    })}
                </ul>
                {!notice.documents.length && (
                    <Source evidence={notice.evidence} />
                )}
            </>
        );
    const overview = notice.documentSummary;
    const groups = (['common', 'conditional'] as const).filter(
        (kind) =>
            overview &&
            (overview[kind].items.length || overview[kind].remainingCount),
    );
    if (!templates.length && !groups.length)
        return (
            <p className={s.muted}>
                표시할 제출서류 항목이 없습니다. 공식 공고를 확인해 주세요.
            </p>
        );
    const completeGroups =
        overview &&
        groups.length > 0 &&
        groups.every(
            (kind) =>
                (overview[kind].remainingIds?.length ?? 0) ===
                overview[kind].remainingCount,
        );
    const groupedIds = new Set(
        completeGroups
            ? groups.flatMap((kind) => [
                  ...overview[kind].items.map((item) => item.id),
                  ...(overview[kind].remainingIds ?? []),
              ])
            : [],
    );
    const otherDocuments = templates.filter((doc) => !groupedIds.has(doc.id));
    const documentList = otherDocuments.length > 0 && (
        <ul className={s.items}>
            {otherDocuments.map((document) => (
                <li
                    key={document.id}
                    className={s.item}
                    data-result-card
                    onClick={handleDisclosureCardClick}
                >
                    <DocumentDisclosure
                        title={document.title}
                        summary={documentSummary(document, notice)}
                        method={document.method}
                        evidence={document.evidence}
                        kind={
                            document.conditionQuestionId
                                ? '해당자 제출'
                                : '공통 제출'
                        }
                    />
                </li>
            ))}
        </ul>
    );
    if (!overview) return documentList;
    const groupedDocuments = groups.map((kind) => (
        <div key={kind} className={s.coreGroup}>
            <h4>{kind === 'common' ? '공통 서류' : '해당 시 추가 서류'}</h4>
            <CoreDocuments
                notice={notice}
                kind={kind}
                templates={templates}
                factsExpanded={expanded}
                renderDisclosure={(item, template, summary) => (
                    <DocumentDisclosure
                        title={item.title}
                        summary={summary}
                        evidence={item.evidence}
                        method={template?.method}
                    />
                )}
            />
        </div>
    ));
    return (
        <>
            {!expanded && groupedDocuments}
            {overview.reviewRequiredCount > 0 && (
                <p className={s.muted}>
                    {documentReviewCountNote(overview.reviewRequiredCount)}
                </p>
            )}
            {groups.length ? (
                <details
                    className={s.evidence}
                    onToggle={(event) => {
                        if (event.target === event.currentTarget)
                            setExpanded(event.currentTarget.open);
                    }}
                >
                    <summary>전체 제출서류와 공식 근거 보기</summary>
                    {expanded && completeGroups && groupedDocuments}
                    {expanded && documentList}
                    {expanded && !completeGroups && (
                        <>
                            <p className={s.muted}>
                                생략된 서류의 세부 목록은 공식 공고에서 확인해
                                주세요.
                            </p>
                            <EvidenceLink evidence={notice.evidence} />
                        </>
                    )}
                </details>
            ) : (
                documentList
            )}
        </>
    );
}

function basicInfoRows(
    notice: Notice,
): (SupportSummaryRow & { content?: ReactNode })[] {
    const summary = noticeSupportSummaryRows(notice);
    const rows = [
        {
            label: '운영기관',
            text: noticeAgency(notice.agency).name,
            content: <AgencyMark agency={notice.agency} />,
        },
        ...noticeHousingFacts(notice),
    ];
    if (summary[0]?.label) return [...rows, ...summary];
    return [
        ...rows,
        { label: '요약', text: summary.map((row) => row.text).join('\n') },
    ];
}

function BasicInfoRows({ notice, other }: { notice: Notice; other?: Notice }) {
    const otherRows = other ? basicInfoRows(other) : [];
    return basicInfoRows(notice).map(({ label, text, content }) => {
        const counterpart = otherRows.find((row) => row.label === label);
        return (
            <div key={label}>
                <dt>
                    {label}
                    {counterpart && counterpart.text !== text && (
                        <span className={s.difference}>서로 다름</span>
                    )}
                </dt>
                <dd>{content ?? (text || '확인 필요')}</dd>
            </div>
        );
    });
}

export function ComparisonContent({ entries }: { entries: ComparisonEntry[] }) {
    const resultsRef = useResultCardScroll('[data-comparison-content]');
    const notices = entries.flatMap((entry) =>
        entry.data ? [entry.data.notice] : [],
    );
    return (
        <div ref={resultsRef} className={s.content} data-comparison-content>
            <Section title="기본 정보" entries={entries}>
                {({ notice }) => (
                    <>
                        <NoticeLifecycleNotice notice={notice} />
                        <dl className={s.facts}>
                            <BasicInfoRows
                                notice={notice}
                                other={notices.find(
                                    (item) => item.id !== notice.id,
                                )}
                            />
                            <div>
                                <dt>접수 상태</dt>
                                <dd>
                                    <ApplicationBadge notice={notice} />
                                </dd>
                            </div>
                            <div>
                                <dt>준비 안내</dt>
                                <dd>
                                    <GuideBadge notice={notice} />
                                </dd>
                            </div>
                            {(notice.guideState !== 'AVAILABLE' ||
                                notice.guideVersion === null) && (
                                <div>
                                    <dt>자료 기준</dt>
                                    <dd>준비 안내 공개 보류</dd>
                                </div>
                            )}
                        </dl>
                        {noticeSupportSummaryRows(notice).some(
                            (row) => row.details?.length,
                        ) ? (
                            <Source
                                evidence={notice.evidence}
                                detail={noticeSupportSummaryRows(notice)
                                    .flatMap((row) => row.details ?? [])
                                    .join('\n')}
                            />
                        ) : (
                            <EvidenceLink evidence={notice.evidence} />
                        )}
                    </>
                )}
            </Section>
            <Section
                title="신청 자격과 선정 순위"
                entries={entries}
                expandableColumn
            >
                {({ notice }) => <Qualifications notice={notice} />}
            </Section>
            <Section title="조건" entries={entries}>
                {({ notice }) => (
                    <>
                        <AreaReviewIssues
                            area="CONDITION"
                            issues={notice.reviewIssues}
                        />
                        <Conditions notice={notice} />
                    </>
                )}
            </Section>
            <Section title="제출서류" entries={entries} expandableColumn>
                {(data) => (
                    <>
                        <AreaReviewIssues
                            area="DOCUMENT"
                            issues={data.notice.reviewIssues}
                        />
                        <Documents {...data} />
                    </>
                )}
            </Section>
            <Section title="공식 일정" entries={entries} expandableColumn>
                {({ notice }) => (
                    <>
                        <ScheduleSummary source={notice} />
                        <AreaReviewIssues
                            area="SCHEDULE"
                            issues={notice.reviewIssues}
                        />
                        <details className={s.evidence}>
                            <summary>세부 일정·공식 근거 보기</summary>
                            <ul className={s.items}>
                                {comparisonSchedules(notice).map((event) => (
                                    <li
                                        className={s.item}
                                        key={event.id}
                                        data-result-card
                                        onClick={
                                            event.evidence
                                                ? handleDisclosureCardClick
                                                : undefined
                                        }
                                    >
                                        <ScheduleCard
                                            heading="h4"
                                            event={event}
                                            evidence={event.evidence}
                                        />
                                    </li>
                                ))}
                            </ul>
                        </details>
                    </>
                )}
            </Section>
        </div>
    );
}
