import { ScheduleSummary, ScheduleCard } from './ScheduleCard';
import { CalendarExport } from '../preparation/CalendarExport';
import { noticeDetailDates } from './noticeDetailDates';
import { DocumentCard } from './DocumentCard';
import { documentSummary, listedDocument } from './documentSummary';
import { EvidenceLink } from './EvidenceLink';
import { NoticeSourceInformation } from './NoticeSourceInformation';
import { NoticeSupportSummary } from './NoticeSupportSummary';
import { NoticeQualificationSummary } from './NoticeQualificationSummary';
import { ConditionSummary } from './ConditionSummary';
import { noticeConditionQuestions } from './conditionOverview';
import { NoticeDocumentSummary } from './NoticeDocumentSummary';
import {
    NoticeDetailScrollSection,
    NoticeDetailScrollDetails,
} from './NoticeDetailScrollArea';
import type { NoticeSourceInformationState } from './useNoticeSourceInformation';
import { InlineExplanation } from '../../shared/ui/ui';
import { AreaReviewIssues } from './ReviewIssues';
import { questionReviewIssues } from './reviewIssueSelectors';
import { cx } from '../../shared/styles/classes';
import { useNoticeDetailSelection } from './useNoticeDetailSelection';
import type { Notice, Schedule } from './types';
import styles from './NoticeDetail.module.css';
import type { NoticeDetailTab } from './noticeDetailTabs';
export type { NoticeDetailTab } from './noticeDetailTabs';

function NoticeScheduleEvent({ event }: { event: Omit<Schedule, 'recorded'> }) {
    const { cardRef, onClick } = useNoticeDetailSelection<HTMLDetailsElement>();
    return (
        <div className={styles.source}>
            <ScheduleCard
                event={event}
                heading="h3"
                evidence={event.evidence}
                detailsProps={{ ref: cardRef, onClick }}
            />
        </div>
    );
}

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
        const { closing: deadline } = noticeDetailDates(notice);
        const calendar = <CalendarExport notice={notice} inline />;
        return (
            <>
                <NoticeDetailScrollSection
                    className={`${styles.contentCard} ${cx('explanation-card')}`}
                >
                    <h2 className={styles.disclosureTitle}>공식 접수 일정</h2>
                    <ScheduleSummary
                        source={notice}
                        showPublished
                        showClosing
                    />
                    {calendar}
                    <AreaReviewIssues
                        area="SCHEDULE"
                        issues={notice.reviewIssues}
                    />
                    {notice.schedules
                        ?.filter((event) => event !== deadline)
                        .map((event) => (
                            <NoticeScheduleEvent key={event.id} event={event} />
                        ))}
                    <InlineExplanation label="일정 공식 근거 보기">
                        {deadline && (
                            <EvidenceLink evidence={deadline.evidence} />
                        )}
                        <EvidenceLink evidence={notice.evidence} />
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
                <AreaReviewIssues
                    issues={notice.reviewIssues}
                    area="CONDITION"
                />
                {noticeConditionQuestions(notice.questions).map((question) => (
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
    const sources = notice.documentTemplates?.length
        ? notice.documentTemplates
        : notice.documents.map((title) => listedDocument(title, notice));
    const documents = sources.map((doc) => ({
        id: doc.id,
        title: doc.title,
        ...documentSummary(doc, notice),
    }));
    return (
        <NoticeDetailScrollSection
            className={`${styles.contentCard} ${cx('explanation-card')}`}
        >
            <h2 className={styles.disclosureTitle}>제출서류</h2>
            <AreaReviewIssues issues={notice.reviewIssues} area="DOCUMENT" />
            <ul className={styles.documents}>
                {documents.map((document) => (
                    <li key={document.id}>
                        <DocumentCard
                            disclosure={NoticeDetailScrollDetails}
                            title={document.title}
                            summary={document}
                        >
                            {document.criteria.length > 2 && (
                                <p>{document.criteria.join(' 또는 ')}</p>
                            )}
                        </DocumentCard>
                    </li>
                ))}
            </ul>
            <InlineExplanation label="제출서류 공식 근거 보기">
                <EvidenceLink evidence={notice.evidence} />
            </InlineExplanation>
        </NoticeDetailScrollSection>
    );
}
