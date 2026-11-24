import { GuideBadge } from './NoticeStatusBadges';
import { guidanceStatus } from './noticeLabels';
import { NoticeReviewDates } from './NoticeReviewDates';
import { Icon } from '../../shared/ui/Icon';
import type { Notice } from './types';
import { closesWhenFilled, scheduleDay, untilFilledLabel } from './schedules';
import { ReviewIssues } from './ReviewIssues';
import styles from './NoticeDetail.module.css';

function scheduleStatus(notice: Notice) {
    if (closesWhenFilled(notice)) return untilFilledLabel;
    if (notice.startDate && notice.endDate) return '접수 기간 확인됨';
    if (notice.startDate) return '접수 시작일 확인됨';
    if (notice.endDate) return '접수 마감일 확인됨';
    if (notice.schedules?.some((event) => scheduleDay(event.date)))
        return '공개된 일정 있음';
    return '확인된 일정 없음';
}

export function NoticeGuidanceStatus({
    notice,
    onExpandedChange,
}: {
    notice: Notice;
    onExpandedChange?: (expanded: boolean) => void;
}) {
    const guideStatus = guidanceStatus(notice).label;
    let conditions = guideStatus;
    let documents = guideStatus;
    if (notice.guideState === 'AVAILABLE') {
        conditions = notice.questions.length
            ? `확인 질문 ${notice.questions.length}개`
            : '공개된 질문 없음';
        documents = notice.documents.length
            ? `제출서류 목록 ${notice.documents.length}종`
            : '공개된 서류 목록 없음';
    }
    const rows = [
        ['조건', conditions],
        ['서류', documents],
        ['일정', scheduleStatus(notice)],
    ];
    return (
        <div className={styles.guidanceStatus}>
            <details
                data-card-disclosure
                onToggle={(event) =>
                    onExpandedChange?.(event.currentTarget.open)
                }
            >
                <summary>
                    준비 안내 확인 현황
                    <span className={styles.guidanceChevron}>
                        <Icon name="down" />
                    </span>
                </summary>
                <div className={styles.guidanceBody}>
                    <dl className={styles.guidanceFacts}>
                        {rows.map(([label, value]) => (
                            <div key={label}>
                                <dt>{label}</dt>
                                <dd>{value}</dd>
                            </div>
                        ))}
                    </dl>
                    <NoticeReviewDates notice={notice} />
                    <ReviewIssues issues={notice.reviewIssues ?? []} />
                </div>
            </details>
            <div className={styles.guidanceBadge}>
                <GuideBadge notice={notice} />
            </div>
        </div>
    );
}
