import { applicationInterrupted, lifecycleLabel } from './noticeLifecycle';
import { noticeDaysLeft } from './noticeDisplay';
import type { NoticeListItem } from './types';
import styles from './NoticeCardDeadline.module.css';

export function NoticeCardDeadline({ notice }: { notice: NoticeListItem }) {
    const remaining = noticeDaysLeft(notice);
    const closed = remaining === '일정 경과' || applicationInterrupted(notice);
    return (
        <dl className={styles.deadline}>
            <dt>
                {applicationInterrupted(notice)
                    ? lifecycleLabel(notice)
                    : closed
                      ? '신청 마감'
                      : '신청 기한'}
            </dt>
            {!closed && <dd>{remaining}</dd>}
        </dl>
    );
}
