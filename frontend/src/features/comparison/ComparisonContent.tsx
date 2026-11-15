import { NoticeLifecycleNotice } from '../housing/NoticeLifecycleNotice';
import type { ReactNode } from 'react';
import { answerLabel } from '../housing/questions';
import { ConditionSummary } from '../housing/ConditionSummary';
import { questionReviewIssues } from '../housing/reviewIssueSelectors';
import type { Evidence, Notice } from '../housing/types';
import { EvidenceLink } from '../housing/EvidenceLink';
import { displayDate } from '../housing/noticeDisplay';
import { qualificationSummaryRows } from '../housing/qualificationSummaryRows';
import { supportSummaryRows } from '../housing/supportSummaryRows';
import { guideLabels } from '../housing/guideLabels';
import { CoreDocuments } from '../housing/CoreDocuments';
import { DocumentIssuanceLinks } from '../housing/DocumentIssuanceLinks';
import { onlineIssuanceLinks } from '../housing/onlineIssuance';
import type { DocumentRequirement, NoticeComparison } from './comparisonApi';
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
            {detail && <p>{detail}</p>}
            <EvidenceLink evidence={evidence} checkedAtOnNewLine />
        </details>
    );
}

function Section({
    title,
    entries,
    children,
}: {
    title: string;
    entries: ComparisonEntry[];
    children: (data: NoticeComparison) => ReactNode;
}) {
    return (
        <section className={s.section} aria-label={title}>
            <h2>{title}</h2>
            <div className={s.pair}>
                {entries.map(({ slot, data }) => (
                    <div className={s.column} key={slot}>
                        <h3>
                            <span className={s.slotNumber}>
                                {slot === 'a' ? '1' : '2'}
                            </span>
                            {data?.notice.title ?? '공고 선택 필요'}
                        </h3>
                        {data ? (
                            children(data)
                        ) : (
                            <p className={s.muted}>
                                공고를 선택하고 불러오면 비교 항목을 표시합니다.
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
        <div className={s.item}>
            <p>
                <strong>{guideLabels[notice.guideState]}</strong>
            </p>
            <p className={s.muted}>
                검수된 조건·서류 안내가 공개되면 비교할 수 있습니다. 해당 항목이
                없다는 뜻은 아닙니다.
            </p>
            <Source evidence={notice.evidence} />
        </div>
    );
}

function Qualifications({ notice }: { notice: Notice }) {
    if (notice.guideState !== 'AVAILABLE')
        return <GuidanceUnavailable notice={notice} />;
    const overview = notice.qualificationSummary;
    return (
        <>
            <dl className={s.facts}>
                {qualificationSummaryRows(overview).map(({ label, text }) => (
                    <div key={label}>
                        <dt>{label}</dt>
                        <dd>{text}</dd>
                    </div>
                ))}
            </dl>
            <Source
                detail={overview?.detail}
                evidence={overview?.evidence ?? notice.evidence}
            />
        </>
    );
}

function Conditions({ notice }: { notice: Notice }) {
    if (notice.guideState !== 'AVAILABLE')
        return <GuidanceUnavailable notice={notice} />;
    if (!notice.questions.length)
        return (
            <p className={s.muted}>
                공개된 조건 항목이 없습니다. 공식 공고를 확인해 주세요.
            </p>
        );
    return (
        <ul className={s.items}>
            {notice.questions.map((question) => (
                <li className={s.item} key={question.id}>
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

function Document({
    document,
    notice,
}: {
    document: DocumentRequirement;
    notice: Notice;
}) {
    const question = notice.questions.find(
        (item) => item.id === document.conditionQuestionId,
    );
    return (
        <li className={s.item}>
            <span className={s.kind}>
                {document.conditionQuestionId ? '해당자 제출' : '공통 제출'}
            </span>
            <h4>{document.title}</h4>
            {document.conditionQuestionId && (
                <p className={s.muted}>
                    관련 조건: {question?.title ?? '공식 공고 확인 필요'}
                    {question && document.conditionAnswerValues?.length
                        ? ' · ' +
                          document.conditionAnswerValues
                              .map((answer) => answerLabel(question, answer))
                              .join(' / ')
                        : ''}
                </p>
            )}
            <dl className={s.facts}>
                <div>
                    <dt>발급·제출 기준</dt>
                    <dd>{document.method || '확인 필요'}</dd>
                </div>
                <div>
                    <dt>발급 기관</dt>
                    <dd>{document.issuer || '확인 필요'}</dd>
                </div>
            </dl>
            <DocumentIssuanceLinks links={onlineIssuanceLinks(document)} />
            <Source evidence={document.evidence} />
        </li>
    );
}

function Documents({ notice, documentRequirements }: NoticeComparison) {
    if (notice.guideState !== 'AVAILABLE')
        return <GuidanceUnavailable notice={notice} />;
    if (documentRequirements === null)
        return (
            <>
                <p className={s.muted}>
                    현재 자료에는 서류명만 있습니다. 제출 구분·발급 기준은 공식
                    근거에서 확인해 주세요.
                </p>
                <ul>
                    {notice.documents.map((title) => (
                        <li key={title}>{title}</li>
                    ))}
                </ul>
                <Source evidence={notice.evidence} />
            </>
        );
    if (!documentRequirements.length)
        return (
            <p className={s.muted}>
                공개된 제출서류 항목이 없습니다. 공식 공고를 확인해 주세요.
            </p>
        );
    const fullList = (
        <ul className={s.items}>
            {documentRequirements.map((document) => (
                <Document
                    key={document.id}
                    document={document}
                    notice={notice}
                />
            ))}
        </ul>
    );
    const overview = notice.documentSummary;
    if (!overview) return fullList;
    const groups = (['common', 'conditional'] as const).filter(
        (kind) => overview[kind].items.length > 0,
    );
    return (
        <>
            {groups.map((kind) => (
                <div key={kind} className={s.coreGroup}>
                    <h4>
                        {kind === 'common'
                            ? '공통 핵심 서류'
                            : '해당 시 추가 서류'}
                    </h4>
                    <CoreDocuments
                        notice={notice}
                        kind={kind}
                        templates={documentRequirements}
                    />
                </div>
            ))}
            {overview.reviewRequiredCount > 0 && (
                <p className={s.muted}>
                    {overview.reviewRequiredCount}개 서류는 제출 기준 확인이
                    필요합니다.
                </p>
            )}
            {groups.length ? (
                <details className={s.evidence}>
                    <summary>전체 제출서류와 공식 근거 보기</summary>
                    {fullList}
                </details>
            ) : (
                fullList
            )}
        </>
    );
}

function basicInfoRows(notice: Notice) {
    const summary = supportSummaryRows(notice.summary ?? '');
    const rows = [
        { label: '운영기관', text: notice.agency },
        { label: '지역', text: notice.region },
        { label: '주택 유형', text: notice.type },
    ];
    if (summary[0]?.label) return [...rows, ...summary];
    return [
        ...rows,
        { label: '요약', text: summary.map((row) => row.text).join('\n') },
    ];
}

function BasicInfoRows({ notice, other }: { notice: Notice; other?: Notice }) {
    const otherRows = other ? basicInfoRows(other) : [];
    return basicInfoRows(notice).map(({ label, text }) => {
        const counterpart = otherRows.find((row) => row.label === label);
        return (
            <div key={label}>
                <dt>
                    {label}
                    {counterpart && counterpart.text !== text && (
                        <span className={s.difference}>서로 다름</span>
                    )}
                </dt>
                <dd>{text || '확인 필요'}</dd>
            </div>
        );
    });
}

export function ComparisonContent({ entries }: { entries: ComparisonEntry[] }) {
    const notices = entries.flatMap((entry) =>
        entry.data ? [entry.data.notice] : [],
    );
    return (
        <>
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
                                <dt>준비 안내</dt>
                                <dd>{guideLabels[notice.guideState]}</dd>
                            </div>
                            <div>
                                <dt>자료 기준</dt>
                                <dd>
                                    기본 정보 v{notice.version}
                                    {notice.guideState === 'AVAILABLE' &&
                                    notice.guideVersion !== null
                                        ? ` · 준비 안내 v${notice.guideVersion}`
                                        : ' · 준비 안내 공개 보류'}
                                </dd>
                            </div>
                        </dl>
                        <EvidenceLink
                            evidence={notice.evidence}
                            checkedAtOnNewLine
                        />
                    </>
                )}
            </Section>
            <Section title="신청 자격과 선정 순위" entries={entries}>
                {({ notice }) => <Qualifications notice={notice} />}
            </Section>
            <Section title="조건" entries={entries}>
                {({ notice }) => <Conditions notice={notice} />}
            </Section>
            <Section title="제출서류" entries={entries}>
                {(data) => <Documents {...data} />}
            </Section>
            <Section title="공식 일정" entries={entries}>
                {({ notice }) => (
                    <ul className={s.items}>
                        {comparisonSchedules(notice).map((event) => (
                            <li className={s.item} key={event.id}>
                                <h4>{event.title}</h4>
                                <p>{displayDate(event.date)}</p>
                                {event.evidence && (
                                    <Source evidence={event.evidence} />
                                )}
                            </li>
                        ))}
                    </ul>
                )}
            </Section>
        </>
    );
}
