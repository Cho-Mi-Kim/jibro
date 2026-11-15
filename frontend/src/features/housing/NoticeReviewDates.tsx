import type { Notice } from './types';
import styles from './NoticeReviewDates.module.css';

function checkedDate(value?: string | null) {
    if (!value) return '확인 기록 없음';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '확인 기록 없음';
    return new Intl.DateTimeFormat('ko-KR', {
        timeZone: 'Asia/Seoul',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
    }).format(date);
}

export function NoticeReviewDates({ notice }: { notice: Notice }) {
    return (
        <dl className={styles.dates} aria-label="공고 확인 시각">
            <div>
                <dt>기본 정보 확인</dt>
                <dd>{checkedDate(notice.basicCheckedAt)}</dd>
            </div>
            <div>
                <dt>준비 안내 검수</dt>
                <dd>{checkedDate(notice.guideCheckedAt)}</dd>
            </div>
            <div>
                <dt>자동 수집</dt>
                <dd>{checkedDate(notice.collectedAt)}</dd>
            </div>
        </dl>
    );
}
