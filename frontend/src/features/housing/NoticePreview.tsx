import { Link } from 'react-router';
import { useNotice } from '../../shared/ui/housingHelpers';
import { State } from '../../shared/ui/ui';
import { Icon } from '../../shared/ui/Icon';
import { displayDate } from '../../shared/ui/housingHelpers';
import styles from './NoticePreview.module.css';
export function NoticePreview({ id, search }: { id: string; search: string }) {
    const query = useNotice(id);
    return (
        <section
            id={'notice-preview-' + id}
            aria-label="공고 미리보기"
            className={styles.preview}
        >
            <State
                loading={query.isPending}
                error={query.error}
                retry={() => void query.refetch()}
            />
            {query.isSuccess && (
                <>
                    <dl className={styles.facts}>
                        <div>
                            <Icon name="calendar" />
                            <dt>신청 마감일</dt>
                            <dd>{displayDate(query.data.endDate)}</dd>
                        </div>
                        <div>
                            <Icon name="pin" />
                            <dt>지역</dt>
                            <dd>{query.data.region}</dd>
                        </div>
                        <div>
                            <Icon name="building" />
                            <dt>주택 유형</dt>
                            <dd>{query.data.type}</dd>
                        </div>
                    </dl>
                    <p className={styles.note}>
                        <Icon name="info" />
                        <span>
                            자격·소득·제출서류는 공고 원문에서 확인하세요.
                        </span>
                    </p>
                    <Link
                        className={styles.detail}
                        to={'/notices/' + id}
                        state={{ noticeSearch: search }}
                    >
                        공고 자세히 보기 <Icon name="arrow" />
                    </Link>
                </>
            )}
        </section>
    );
}
