import { Link, useLocation, useParams, useSearchParams } from 'react-router';
import { getHomeReturn } from '../shared/navigation/useNoticeEntryState';
import { useQuery } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import { NoticeDetailOverview } from '../features/housing/NoticeDetailOverview';
import { useSession } from '../app/useSession';
import {
    NoticeDetailActions,
    NoticeResources,
} from '../features/housing/NoticeDetailActions';
import { NoticeDetailContent } from '../features/housing/NoticeDetailContent';
import {
    noticeDetailTabs,
    type NoticeDetailTab,
} from '../features/housing/noticeDetailTabs';
import { Bookmark } from '../features/member/SavedNotices';
import { ShareNotice } from '../features/housing/ShareNotice';
import { Icon } from '../shared/ui/Icon';
import { State } from '../shared/ui/ui';
import styles from '../features/housing/NoticeDetail.module.css';

export function NoticeDetailPage() {
    const { noticeId = '' } = useParams();
    const { state } = useLocation();
    const [params, setParams] = useSearchParams();
    const homeReturn = getHomeReturn(state);
    const backSearch =
        typeof state?.noticeSearch === 'string' ? state.noticeSearch : '';
    const tab =
        noticeDetailTabs.find(({ value }) => value === params.get('tab'))
            ?.value ?? 'summary';
    function setTab(value: NoticeDetailTab) {
        setParams(value === 'summary' ? {} : { tab: value }, {
            replace: true,
            state,
        });
    }
    const query = useQuery({
        queryKey: ['notice', noticeId],
        queryFn: () => api.notice(noticeId),
    });
    const notice = query.data;
    const { user } = useSession();
    const preparations = useQuery({
        queryKey: ['preparations', user?.id],
        queryFn: api.preparations,
        enabled: !!user && !!notice?.canStart,
    });
    const current = preparations.data?.find(
        (item) =>
            item.noticeId === noticeId && item.version === notice?.version,
    );

    return (
        <div className={styles.page} data-notice-detail>
            <div className={styles.scrollContent} key={noticeId}>
                <header className={styles.header}>
                    <Link
                        className={styles.back}
                        to={
                            homeReturn ?? {
                                pathname: '/notices',
                                search: backSearch,
                            }
                        }
                        replace={!!homeReturn}
                        state={
                            homeReturn ? undefined : { noticeAnchor: noticeId }
                        }
                        aria-label={
                            homeReturn ? '홈으로 돌아가기' : '공고 목록 보기'
                        }
                    >
                        <Icon name="back" />
                    </Link>
                    <h1>공고 상세</h1>
                    <div className={styles.tools}>
                        {notice && (
                            <>
                                <Bookmark id={noticeId} />
                                <ShareNotice url={notice.evidence.url} />
                            </>
                        )}
                    </div>
                </header>
                <State
                    loading={query.isPending}
                    error={query.error}
                    retry={() => void query.refetch()}
                />
                {notice && (
                    <>
                        <NoticeDetailOverview key={notice.id} notice={notice} />
                        <div
                            className={styles.tabs}
                            role="tablist"
                            aria-label="공고 정보"
                        >
                            {noticeDetailTabs.map(({ value, label }, index) => (
                                <button
                                    type="button"
                                    role="tab"
                                    id={`notice-tab-${value}`}
                                    aria-controls="notice-panel"
                                    aria-selected={tab === value}
                                    tabIndex={tab === value ? 0 : -1}
                                    key={value}
                                    onClick={() => setTab(value)}
                                    onKeyDown={(event) => {
                                        if (
                                            event.ctrlKey ||
                                            event.metaKey ||
                                            event.altKey
                                        )
                                            return;
                                        const direction =
                                            event.key === 'ArrowRight'
                                                ? 1
                                                : event.key === 'ArrowLeft'
                                                  ? -1
                                                  : 0;
                                        if (
                                            !direction &&
                                            event.key !== 'Home' &&
                                            event.key !== 'End'
                                        )
                                            return;
                                        event.preventDefault();
                                        const next =
                                            event.key === 'Home'
                                                ? 0
                                                : event.key === 'End'
                                                  ? noticeDetailTabs.length - 1
                                                  : (index +
                                                        direction +
                                                        noticeDetailTabs.length) %
                                                    noticeDetailTabs.length;
                                        setTab(noticeDetailTabs[next].value);
                                        document
                                            .getElementById(
                                                `notice-tab-${noticeDetailTabs[next].value}`,
                                            )
                                            ?.focus();
                                    }}
                                >
                                    {label}
                                </button>
                            ))}
                        </div>
                        <section
                            id="notice-panel"
                            className={styles.content}
                            role="tabpanel"
                            aria-labelledby={`notice-tab-${tab}`}
                            tabIndex={0}
                        >
                            <NoticeDetailContent
                                notice={notice}
                                tab={tab}
                                onTabChange={setTab}
                            />
                        </section>
                        <NoticeResources notice={notice} />
                    </>
                )}
            </div>
            {notice && (
                <NoticeDetailActions
                    notice={notice}
                    preparationId={current?.id}
                />
            )}
        </div>
    );
}
