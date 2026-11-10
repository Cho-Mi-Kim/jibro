import { daysLeft } from '../../shared/ui/housingHelpers';
import type { Notice } from './types';
import styles from './NoticeCardDeadline.module.css';

export function NoticeCardDeadline({ notice }: { notice: Notice }) {
    const remaining = daysLeft(notice.endDate);
    const closed = remaining === '일정 경과';
    return (
        <dl className={styles.deadline}>
            <dt>{closed ? '신청 마감' : '신청 기한'}</dt>
            {!closed && <dd>{remaining}</dd>}
        </dl>
    );
}
