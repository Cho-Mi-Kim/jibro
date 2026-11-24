import type { Guide } from '../housing/types';
import styles from './GuideAnswer.module.css';

const dateFormatter = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
});

function displayDate(value: string | undefined) {
    if (!value) return undefined;
    const date = new Date(value);
    if (!Number.isFinite(date.getTime())) return undefined;
    const parts = dateFormatter.formatToParts(date);
    return ['year', 'month', 'day']
        .map((type) => parts.find((part) => part.type === type)?.value)
        .join('-');
}

export function GuideFreshnessNotice({
    freshness,
}: {
    freshness: Guide['freshness'];
}) {
    if (!freshness || freshness.status === 'CURRENT') return null;
    const unreachable = freshness.status === 'UNREACHABLE';
    const checkedAt = displayDate(freshness.checkedAt);
    const attemptedAt = displayDate(freshness.lastAttemptAt);
    return (
        <aside className={styles.recheck} aria-label="가이드 확인 상태">
            <strong>
                {unreachable
                    ? '공식 자료에 연결하지 못했어요.'
                    : '최신 내용 확인이 필요해요.'}
            </strong>
            <p>
                {unreachable
                    ? '아래는 마지막으로 확인한 안내예요. 접속이 다시 되면 내용을 재확인해요.'
                    : '아래는 이전에 확인한 안내예요. 바뀐 기준이 반영되기 전일 수 있으니 신청 전 공식 자료를 확인해 주세요.'}
            </p>
            {(checkedAt || attemptedAt) && (
                <div className={styles.recheckDates}>
                    {checkedAt && <span>마지막 내용 확인 {checkedAt}</span>}
                    {attemptedAt && <span>최근 확인 시도 {attemptedAt}</span>}
                </div>
            )}
        </aside>
    );
}
