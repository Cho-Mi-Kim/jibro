import { applicationInterrupted, lifecycleLabel } from './noticeLifecycle';
import { displayDate, noticeDaysLeft } from './noticeDisplay';
import { Icon } from '../../shared/ui/Icon';
import type { NoticeListItem } from './types';
import styles from './NoticeCardDeadline.module.css';
import { closesWhenFilled, untilFilledLabel } from './schedules';

export function NoticeCardClosingDate({ notice }: { notice: NoticeListItem }) {
    return (
        <div>
            <dt>
                <Icon name="calendar" />
                {applicationInterrupted(notice) ? '기존 마감일' : '신청 마감일'}
            </dt>
            <dd>
                {closesWhenFilled(notice)
                    ? untilFilledLabel
                    : displayDate(notice.endDate)}
            </dd>
        </div>
    );
}

export function NoticeCardDeadline({
    notice,
    includeDate = false,
}: {
    notice: NoticeListItem;
    includeDate?: boolean;
}) {
    const remaining = noticeDaysLeft(notice);
    const closed = remaining === '일정 경과' || applicationInterrupted(notice);
    return (
        <dl className={styles.deadline}>
            <div>
                <dt>
                    {applicationInterrupted(notice)
                        ? lifecycleLabel(notice)
                        : '신청 마감'}
                </dt>
                {!closed && <dd>{remaining}</dd>}
            </div>
            {includeDate && <NoticeCardClosingDate notice={notice} />}
        </dl>
    );
}
