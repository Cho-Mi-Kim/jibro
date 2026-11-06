import type { ReactNode } from 'react';
import { CalendarExport } from '../preparation/CalendarExport';
import { applicationDeadline, noticeDetailDates } from './noticeDetailDates';
import { Disc, OfficialLink } from '../../shared/ui/HousingUI';
import { Icon } from '../../shared/ui/Icon';
import { displayDate } from '../../shared/ui/housingHelpers';
import { guideLabels } from '../../shared/ui/helpers';
import { EvidenceLink, InlineExplanation } from '../../shared/ui/ui';
import { cx } from '../../shared/styles/classes';
import type { Notice } from './types';
import styles from './NoticeDetail.module.css';
import type { NoticeDetailTab } from './noticeDetailTabs';
export type { NoticeDetailTab } from './noticeDetailTabs';

function SummaryDisclosure({
    title,
    answer,
    children,
}: {
    title: string;
    answer: string;
    children: ReactNode;
}) {
    return (
        <section className={styles.summaryCard}>
            <Disc name="file" />
            <details className={styles.summaryDetails}>
                <summary>
                    <h2>{title}</h2>
                    <Icon name="down" />
                    <span>{answer}</span>
                </summary>
                {children}
            </details>
        </section>
    );
}

function GuidanceUnavailable({ notice }: { notice: Notice }) {
    return (
        <section className={styles.contentCard}>
            <h2>{guideLabels[notice.guideState]}</h2>
            <p>
                검수되지 않은 개인별 안내는 제공하지 않습니다. 지금은 공식
                공고에서 세부 기준을 확인해 주세요.
            </p>
            <OfficialLink url={notice.evidence.url}>
                공식 공고 보기
            </OfficialLink>
        </section>
    );
}

export function NoticeDetailContent({
    notice,
    tab,
    onTabChange,
}: {
    notice: Notice;
    tab: NoticeDetailTab;
    onTabChange: (tab: NoticeDetailTab) => void;
}) {
    if (tab === 'summary') {
        const sentences = notice.summary.split(/(?<=[.!?])\s+/);
        return (
            <>
                <SummaryDisclosure
                    title="어떤 지원인가요?"
                    answer={sentences[0]}
                >
                    <p>
                        {sentences.slice(1).join(' ') ||
                            '세부 기준은 아래 공식 공고를 확인해 주세요.'}
                    </p>
                    <OfficialLink url={notice.evidence.url} className="">
                        <Icon name="file" />
                        {notice.evidence.label}
                    </OfficialLink>
                </SummaryDisclosure>
                {notice.guideState === 'AVAILABLE' ? (
                    <>
                        {(
                            [
                                [
                                    'conditions',
                                    '자격과 순위를 구분해요',
                                    '신청 자격과 선정 순위는 서로 다른 기준이에요.',
                                    '조건과 공식 근거 확인',
                                ],
                                [
                                    'documents',
                                    '필요한 서류 확인',
                                    '공통 서류와 해당자 제출 서류를 나누어 확인해요.',
                                    '제출서류 확인',
                                ],
                            ] as const
                        ).map(([target, title, answer, action]) => (
                            <SummaryDisclosure
                                key={target}
                                title={title}
                                answer={answer}
                            >
                                <button
                                    type="button"
                                    className={styles.cardAction}
                                    onClick={() => onTabChange(target)}
                                >
                                    <Icon name="file" />
                                    {action}
                                </button>
                            </SummaryDisclosure>
                        ))}
                    </>
                ) : (
                    <GuidanceUnavailable notice={notice} />
                )}
            </>
        );
    }
    if (tab === 'schedule') {
        const deadline = applicationDeadline(notice);
        const { start, end, announcement, documents } =
            noticeDetailDates(notice);
        const deadlineNote = [
            end.time,
            /조기\s*중단\s*가능/.test(deadline?.title ?? '') && '조기중단 가능',
        ]
            .filter(Boolean)
            .join(' ');
        const dateRows = [
            ['공고일', displayDate(notice.publishedDate)],
            [
                '접수기간',
                notice.startDate ? `${start.date} ~ ${end.date}` : '확인 필요',
            ],
            [
                '결과 발표',
                [announcement.date, announcement.time]
                    .filter(Boolean)
                    .join(' '),
            ],
            [
                '서류 마감',
                [documents.date, documents.time].filter(Boolean).join(' '),
            ],
            [
                '접수 마감',
                notice.endDate
                    ? end.date + (deadlineNote ? `(${deadlineNote})` : '')
                    : '',
            ],
        ];
        const calendar = <CalendarExport notice={notice} inline />;
        return (
            <section
                className={`${styles.contentCard} ${cx('explanation-card')}`}
            >
                <h2 className={styles.disclosureTitle}>공식 접수 일정</h2>
                <dl className={styles.scheduleDates}>
                    {dateRows.map(([label, date]) => (
                        <div key={label}>
                            <dt>{label}</dt>
                            <dd aria-label={date || '날짜 정보 없음'}>
                                {date}
                            </dd>
                        </div>
                    ))}
                </dl>
                {calendar}
                {notice.schedules
                    ?.filter((event) => event !== deadline)
                    .map((event) => (
                        <div key={event.id} className={styles.source}>
                            <h3>{event.title}</h3>
                            <p>{displayDate(event.date)}</p>
                        </div>
                    ))}
                <InlineExplanation label="일정 공식 근거 보기">
                    {notice.schedules?.map((event) => (
                        <EvidenceLink
                            key={event.id}
                            className={styles.evidence}
                            evidence={event.evidence}
                        />
                    ))}
                    <EvidenceLink
                        className={styles.evidence}
                        evidence={notice.evidence}
                    />
                </InlineExplanation>
            </section>
        );
    }
    if (notice.guideState !== 'AVAILABLE')
        return <GuidanceUnavailable notice={notice} />;
    if (tab === 'conditions')
        return (
            <>
                {notice.questions.map((question) => (
                    <section className={styles.contentCard} key={question.id}>
                        <details className={styles.summaryDetails}>
                            <summary>
                                <h2>{question.title}</h2>
                                <Icon name="down" />
                                <span>{question.reason}</span>
                            </summary>
                            <EvidenceLink
                                className={styles.evidence}
                                evidence={question.evidence}
                            />
                        </details>
                    </section>
                ))}
            </>
        );
    return (
        <section className={`${styles.contentCard} ${cx('explanation-card')}`}>
            <h2 className={styles.disclosureTitle}>제출서류</h2>
            <p>
                공통 서류와 해당자 서류를 함께 표시합니다. 모두 제출하는 목록이
                아니며, 내 조건 확인 후 필요한 항목과 발급 방법을 확인하세요.
            </p>
            <ul className={styles.documents}>
                {notice.documents.map((document) => (
                    <li key={document}>
                        <Icon name="file" />
                        {document}
                    </li>
                ))}
            </ul>
            <InlineExplanation label="제출서류 공식 근거 보기">
                <EvidenceLink
                    className={styles.evidence}
                    evidence={notice.evidence}
                />
            </InlineExplanation>
        </section>
    );
}
