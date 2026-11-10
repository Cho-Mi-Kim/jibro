import { guideLabels } from '../../shared/ui/helpers';
import { displayDate } from '../../shared/ui/housingHelpers';
import { Icon } from '../../shared/ui/Icon';
import type { Notice } from './types';
import { scheduleDay } from './schedules';
import styles from './NoticeDetail.module.css';

function scheduleStatus(notice: Notice) {
    if (notice.startDate && notice.endDate) return '접수 기간 확인됨';
    if (notice.startDate) return '접수 시작일 확인됨';
    if (notice.endDate) return '접수 마감일 확인됨';
    if (notice.schedules?.some((event) => scheduleDay(event.date)))
        return '공개된 일정 있음';
    return '확인된 일정 없음';
}

export function NoticeGuidanceStatus({ notice }: { notice: Notice }) {
    const guideStatus = guideLabels[notice.guideState];
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
        <details className={styles.guidanceStatus}>
            <summary>
                준비 안내 확인 현황
                <span className={styles.guidanceChevron}>
                    <Icon name="down" />
                </span>
            </summary>
            <div className={styles.guidanceBody}>
                <dl>
                    {rows.map(([label, value]) => (
                        <div key={label}>
                            <dt>{label}</dt>
                            <dd>{value}</dd>
                        </div>
                    ))}
                </dl>
                <p>
                    공고 기본 정보 확인일 ·{' '}
                    {displayDate(notice.evidence.checkedAt)}
                    <br />
                    기본 정보 v{notice.version}
                    {notice.guideVersion != null && (
                        <> · 준비 안내 v{notice.guideVersion}</>
                    )}
                </p>
            </div>
        </details>
    );
}
