import { NoticeCardClosingDate } from './NoticeCardDeadline';
import { NoticeHousingRows } from './NoticeHousingRows';
import { NoticeLifecycleNotice } from './NoticeLifecycleNotice';
import { Link, useLocation } from 'react-router';
import type { NoticeListItem } from './types';
import { Icon } from '../../shared/ui/Icon';
import styles from './NoticePreview.module.css';
export function NoticePreview({
    notice,
    onOpenDetail,
}: {
    notice: NoticeListItem;
    onOpenDetail?: () => void;
}) {
    const { pathname, search, key } = useLocation();
    const id = notice.id;
    return (
        <section
            id={'notice-preview-' + id}
            aria-label="공고 미리보기"
            className={styles.preview}
        >
            <dl className={styles.facts}>
                <NoticeCardClosingDate notice={notice} />
                <NoticeHousingRows notice={notice} />
            </dl>
            <NoticeLifecycleNotice notice={notice} />
            <p className={styles.note}>
                <Icon name="info" />
                <span>자격·소득·제출서류는 공고 원문에서 확인하세요.</span>
            </p>
            <Link
                className={styles.detail}
                to={'/notices/' + id}
                onClick={onOpenDetail}
                state={{
                    noticeSearch: search,
                    noticeListKey: key,
                    fromSaved: pathname === '/saved',
                }}
            >
                공고 자세히 보기 <Icon name="arrow" />
            </Link>
        </section>
    );
}
