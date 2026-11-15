import { IndividualAnnouncement } from './IndividualAnnouncement';
import { CalendarExport } from '../preparation/CalendarExport';
import { applicationDeadline, noticeDetailDates } from './noticeDetailDates';
import { Icon } from '../../shared/ui/Icon';
import { displayDate } from './noticeDisplay';
import { DocumentIssuanceLinks } from './DocumentIssuanceLinks';
import { DocumentSummaryFacts } from './DocumentSummaryFacts';
import { documentSummary } from './documentSummary';
import { onlineIssuanceLinks } from './onlineIssuance';
import { EvidenceLink } from './EvidenceLink';
import { NoticeSourceInformation } from './NoticeSourceInformation';
import { NoticeSupportSummary } from './NoticeSupportSummary';
import { NoticeQualificationSummary } from './NoticeQualificationSummary';
import { ConditionSummary } from './ConditionSummary';
import { NoticeDocumentSummary } from './NoticeDocumentSummary';
import { NoticeDetailScrollSection } from './NoticeDetailScrollArea';
import type { NoticeSourceInformationState } from './useNoticeSourceInformation';
import { InlineExplanation } from '../../shared/ui/ui';
import { ReviewIssues } from './ReviewIssues';
import {
    questionReviewIssues,
    scheduleReviewIssues,
} from './reviewIssueSelectors';
import { cx } from '../../shared/styles/classes';
import type { Notice } from './types';
import styles from './NoticeDetail.module.css';
import type { NoticeDetailTab } from './noticeDetailTabs';
export type { NoticeDetailTab } from './noticeDetailTabs';

export function NoticeDetailContent({
    notice,
    tab,
    onTabChange,
    sourceInformation,
}: {
    notice: Notice;
    tab: NoticeDetailTab;
    onTabChange: (tab: NoticeDetailTab) => void;
    sourceInformation?: NoticeSourceInformationState;
}) {
    if (tab === 'summary') {
        return (
            <>
                <NoticeSupportSummary notice={notice} />
                {notice.guideState === 'AVAILABLE' ? (
                    <>
                        <NoticeQualificationSummary
                            notice={notice}
                            onConditions={() => onTabChange('conditions')}
                        />
                        <NoticeDocumentSummary
                            notice={notice}
                            onDocuments={() => onTabChange('documents')}
                        />
                    </>
                ) : (
                    <NoticeSourceInformation
                        notice={notice}
                        information={sourceInformation}
                    />
                )}
            </>
        );
    }
    if (tab === 'schedule') {
        const deadline = applicationDeadline(notice);
        const { start, end, announcement, documents, announcementExplanation } =
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
            <>
                <NoticeDetailScrollSection
                    className={`${styles.contentCard} ${cx('explanation-card')}`}
                >
                    <h2 className={styles.disclosureTitle}>공식 접수 일정</h2>
                    <dl className={styles.scheduleDates}>
                        {dateRows.map(([label, date]) => (
                            <div key={label}>
                                <dt>{label}</dt>
                                <dd
                                    aria-label={
                                        label === '결과 발표' &&
                                        announcementExplanation
                                            ? `${date} 개별 안내`.trim()
                                            : date || '날짜 정보 없음'
                                    }
                                >
                                    {label === '결과 발표' &&
                                    announcementExplanation ? (
                                        <>
                                            {date}
                                            <IndividualAnnouncement
                                                event={announcementExplanation}
                                            />
                                        </>
                                    ) : (
                                        date
                                    )}
                                </dd>
                            </div>
                        ))}
                    </dl>
                    {calendar}
                    <ReviewIssues
                        issues={scheduleReviewIssues(notice.reviewIssues)}
                    />
                    {notice.schedules
                        ?.filter((event) => event !== deadline)
                        .map((event) => (
                            <div key={event.id} className={styles.source}>
                                <h3>{event.title}</h3>
                                <p>
                                    {event.timingDescription ||
                                        displayDate(event.date)}
                                </p>
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
                </NoticeDetailScrollSection>
                {notice.guideState !== 'AVAILABLE' &&
                    !notice.schedules?.length && (
                        <NoticeSourceInformation
                            notice={notice}
                            information={sourceInformation}
                            area="SCHEDULE"
                            showEmpty={false}
                        />
                    )}
            </>
        );
    }
    if (notice.guideState !== 'AVAILABLE')
        return (
            <NoticeSourceInformation
                notice={notice}
                information={sourceInformation}
                area={tab === 'conditions' ? 'CONDITION' : 'DOCUMENT'}
            />
        );
    if (tab === 'conditions')
        return (
            <>
                {notice.questions.map((question) => (
                    <NoticeDetailScrollSection
                        className={styles.contentCard}
                        key={question.id}
                    >
                        <ConditionSummary
                            question={question}
                            issues={questionReviewIssues(
                                notice.reviewIssues,
                                question.id,
                            )}
                        />
                    </NoticeDetailScrollSection>
                ))}
            </>
        );
    const documents = notice.documentTemplates?.length
        ? notice.documentTemplates.map((doc) => ({
              id: doc.id,
              title: doc.title,
              ...documentSummary(doc, notice),
          }))
        : notice.documents.map((title) => {
              const links = onlineIssuanceLinks({ title, issuer: '' });
              return {
                  id: title,
                  title,
                  audience: '공식 공고에서 제출 대상 확인',
                  method: links[0]?.label ?? '공식 공고의 발급 안내 확인',
                  criteria: [],
                  issues: [],
                  links,
              };
          });
    return (
        <NoticeDetailScrollSection
            className={`${styles.contentCard} ${cx('explanation-card')}`}
        >
            <h2 className={styles.disclosureTitle}>제출서류</h2>
            <ul className={styles.documents}>
                {documents.map((document) => (
                    <li key={document.id}>
                        <Icon name="file" />
                        <div>
                            <strong>{document.title}</strong>
                            <DocumentSummaryFacts summary={document} />
                            {document.criteria.length > 2 && (
                                <InlineExplanation label="제출 대상 전체 보기">
                                    <p>{document.criteria.join(' 또는 ')}</p>
                                </InlineExplanation>
                            )}
                            <DocumentIssuanceLinks links={document.links} />
                            <ReviewIssues issues={document.issues} />
                        </div>
                    </li>
                ))}
            </ul>
            <InlineExplanation label="제출서류 공식 근거 보기">
                <EvidenceLink
                    className={styles.evidence}
                    evidence={notice.evidence}
                />
            </InlineExplanation>
        </NoticeDetailScrollSection>
    );
}
