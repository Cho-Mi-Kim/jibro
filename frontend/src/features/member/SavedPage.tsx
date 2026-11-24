import { Link, useSearchParams } from 'react-router';
import {
    useNoticeListPosition,
    useSavedComparisonReturn,
} from '../housing/NoticeListVisit';
import { Heading, State } from '../../shared/ui/ui';
import { Icon } from '../../shared/ui/Icon';
import { NoticeCard } from '../housing/NoticeCard';
import { NoticeComparisonPage } from '../comparison/NoticeComparisonPage';
import { useSavedNotices } from './useSavedNotices';
import styles from './Controls.module.css';

export function SavedPage() {
    const saved = useSavedNotices(true);
    const {
        list,
        position: returnPosition,
        remember,
        clear,
    } = useNoticeListPosition<HTMLUListElement>(saved.isSuccess);
    const [params, setParams] = useSearchParams();
    const expandedId = params.get('preview');
    function togglePreview(id: string) {
        clear();
        const next = new URLSearchParams(params);
        if (expandedId === id) next.delete('preview');
        else next.set('preview', id);
        setParams(next, { replace: true });
    }
    return (
        <>
            <Heading title="관심 공고" backTo="/my" />
            <State
                loading={saved.isPending}
                error={saved.error}
                retry={() => void saved.refetch()}
            />
            {saved.isSuccess && (
                <section className={styles.savedPage} aria-label="저장한 공고">
                    {saved.data.length > 0 && (
                        <Link
                            className={`${styles.link} ${styles.savedCompare}`}
                            onClick={() => remember()}
                            to={
                                expandedId &&
                                saved.data.some((n) => n.id === expandedId)
                                    ? '/saved/compare?' +
                                      new URLSearchParams({ a: expandedId })
                                    : '/saved/compare'
                            }
                        >
                            <Icon name="file" />
                            공고 비교
                        </Link>
                    )}
                    {!saved.data.length && (
                        <p className={styles.savedEmpty}>
                            저장한 공고가 없습니다.
                        </p>
                    )}
                    <ul
                        ref={list}
                        className={styles.savedList}
                        data-notice-list
                        data-restored-list={returnPosition ? true : undefined}
                    >
                        {saved.data.map((n) => (
                            <li key={n.id}>
                                <NoticeCard
                                    notice={n}
                                    expanded={expandedId === n.id}
                                    toggle={() => togglePreview(n.id)}
                                    showCollapsedDeadline
                                    onOpenDetail={() => remember()}
                                    restoreExpanded={
                                        !!returnPosition && expandedId === n.id
                                    }
                                />
                            </li>
                        ))}
                    </ul>
                    <Link
                        className={`${styles.link} ${styles.savedBrowse}`}
                        to="/notices"
                    >
                        <Icon name="search" />
                        공고 찾기
                    </Link>
                </section>
            )}
        </>
    );
}

export function SavedComparisonPage() {
    const saved = useSavedNotices();
    const backTo = useSavedComparisonReturn();
    if (saved.isSuccess)
        return (
            <NoticeComparisonPage savedNotices={saved.data} backTo={backTo} />
        );
    return (
        <>
            <Heading title="관심 공고 비교" backTo={backTo} />
            <State
                loading={saved.isPending}
                error={saved.error}
                retry={() => void saved.refetch()}
            />
        </>
    );
}
