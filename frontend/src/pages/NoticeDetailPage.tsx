import { ApplicationCompletionBadge } from '../features/preparation/ApplicationCompletionBadge';
import { usePreparationRecord } from '../features/preparation/usePreparationRecord';
import { applicationRecordSchedule } from '../features/housing/schedules';
import { Link, useLocation, useParams, useSearchParams } from 'react-router';
import { getHomeReturn } from '../shared/navigation/useNoticeEntryState';
import { useNotice } from '../features/housing/useNotice';
import { useNoticeDetailPosition } from '../features/housing/NoticeListVisit';
import { usePreparationList } from '../features/preparation/usePreparationList';
import { NoticeDetailOverview } from '../features/housing/NoticeDetailOverview';
import { NoticeCorrectionBanner } from '../features/housing/NoticeCorrectionBanner';
import {
    NoticeDetailActions,
    NoticeResources,
} from '../features/housing/NoticeDetailActions';
import { NoticeDetailContent } from '../features/housing/NoticeDetailContent';
import { useNoticeSourceInformation } from '../features/housing/useNoticeSourceInformation';
import {
    noticeDetailTabs,
    type NoticeDetailTab,
} from '../features/housing/noticeDetailTabs';
import { Bookmark } from '../features/member/SavedNotices';
import { ShareNotice } from '../features/housing/ShareNotice';
import { Icon } from '../shared/ui/Icon';
import { State } from '../shared/ui/ui';
import styles from '../features/housing/NoticeDetail.module.css';

function detailBackLink(
    state: ReturnType<typeof useLocation>['state'],
    noticeId: string,
) {
    const origin = state?.comparisonReturn;
    if (
        (origin?.pathname === '/compare' ||
            origin?.pathname === '/saved/compare') &&
        typeof origin.search === 'string'
    ) {
        return {
            to: { pathname: origin.pathname, search: origin.search },
            replace: true,
            'aria-label': '공고 비교로 돌아가기',
            state: {
                comparisonKey: state.comparisonKey,
                detailReturn: state.detailReturn,
                noticeListKey: state.noticeListKey,
                noticeSearch: state.noticeSearch,
            },
        };
    }
    const home = getHomeReturn(state);
    if (home)
        return { to: home, replace: true, 'aria-label': '홈으로 돌아가기' };
    const search =
        typeof state?.noticeSearch === 'string' ? state.noticeSearch : '';
    if (state?.fromSaved === true)
        return {
            to: { pathname: '/saved', search },
            replace: true,
            'aria-label': '관심 공고로 돌아가기',
        };
    return {
        to: { pathname: '/notices', search },
        state: { noticeAnchor: noticeId, noticeListKey: state?.noticeListKey },
        'aria-label': '공고 목록 보기',
    };
}

export function NoticeDetailPage() {
    const { noticeId = '' } = useParams();
    const { state } = useLocation();
    const [params, setParams] = useSearchParams();
    const tab =
        noticeDetailTabs.find(({ value }) => value === params.get('tab'))
            ?.value ?? 'summary';
    function setTab(value: NoticeDetailTab) {
        setParams(value === 'summary' ? {} : { tab: value }, {
            replace: true,
            state,
        });
    }
    const query = useNotice(noticeId);
    const notice = query.data;
    const sourceInformation = useNoticeSourceInformation(notice);
    const { host, position, remember } = useNoticeDetailPosition(
        !!notice && !sourceInformation.loading,
    );
    const preparations = usePreparationList(!!notice);
    const current = preparations.data?.find(
        (item) =>
            item.noticeId === noticeId && item.version === notice?.version,
    );
    const record = usePreparationRecord(current?.id).data;
    const application =
        record &&
        record.noticeId === noticeId &&
        record.version === notice?.version
            ? applicationRecordSchedule(record.schedules)
            : undefined;

    return (
        <div className={styles.page} data-notice-detail>
            <div
                className={styles.scrollContent}
                ref={host}
                data-restored-list={position ? true : undefined}
                key={noticeId}
                data-notice-detail-scroll
            >
                <header className={styles.header}>
                    <Link
                        className={styles.back}
                        {...detailBackLink(state, noticeId)}
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
                        <NoticeCorrectionBanner notice={notice} />
                        <NoticeDetailOverview
                            key={notice.id}
                            notice={notice}
                            applicationRecord={
                                <ApplicationCompletionBadge
                                    recorded={application?.recorded}
                                />
                            }
                        />
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
                                sourceInformation={sourceInformation}
                            />
                        </section>
                        <NoticeResources
                            notice={notice}
                            onLeaveDetail={remember}
                        />
                    </>
                )}
            </div>
            {notice && (
                <NoticeDetailActions
                    notice={notice}
                    onLeaveDetail={remember}
                    preparationId={current?.id}
                />
            )}
        </div>
    );
}
