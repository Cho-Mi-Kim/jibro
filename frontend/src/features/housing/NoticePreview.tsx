import { applicationInterrupted } from './noticeLifecycle';
import { NoticeLifecycleNotice } from './NoticeLifecycleNotice';
import { Link } from 'react-router';
import type { NoticeListItem } from './types';
import { Icon } from '../../shared/ui/Icon';
import { displayDate } from './noticeDisplay';
import styles from './NoticePreview.module.css';
export function NoticePreview({
    notice,
    search,
}: {
    notice: NoticeListItem;
    search: string;
}) {
    const id = notice.id;
    return (
        <section
            id={'notice-preview-' + id}
            aria-label="공고 미리보기"
            className={styles.preview}
        >
            <dl className={styles.facts}>
                <div>
                    <Icon name="calendar" />
                    <dt>
                        {applicationInterrupted(notice)
                            ? '기존 마감일'
                            : '신청 마감일'}
                    </dt>
                    <dd>{displayDate(notice.endDate)}</dd>
                </div>
                <div>
                    <Icon name="pin" />
                    <dt>지역</dt>
                    <dd>{notice.region}</dd>
                </div>
                <div>
                    <Icon name="building" />
                    <dt>주택 유형</dt>
                    <dd>{notice.type}</dd>
                </div>
            </dl>
            <NoticeLifecycleNotice notice={notice} />
            <p className={styles.note}>
                <Icon name="info" />
                <span>자격·소득·제출서류는 공고 원문에서 확인하세요.</span>
            </p>
            <Link
                className={styles.detail}
                to={'/notices/' + id}
                state={{ noticeSearch: search }}
            >
                공고 자세히 보기 <Icon name="arrow" />
            </Link>
        </section>
    );
}
