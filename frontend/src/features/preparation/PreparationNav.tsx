import { NoticeLifecycleNotice } from '../housing/NoticeLifecycleNotice';
import { useNotice } from '../housing/useNotice';
import { Link, NavLink, useLocation } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { api } from '../housing/api';
import type { Preparation } from '../housing/types';
import { Button } from '../../shared/ui/ui';
import { Icon } from '../../shared/ui/Icon';
import { preparationTabPath, preparationTabs } from './preparationTabRoutes';
import styles from './PreparationHeader.module.css';
import { useSaved } from './usePreparation';
export function GuidanceRefresh({
    p,
    onRefreshed,
    showUnavailable = false,
}: {
    p: Preparation;
    onRefreshed?: () => void;
    showUnavailable?: boolean;
}) {
    const notice = useNotice(p.noticeId);
    const saved = useSaved();
    const refresh = useMutation({
        mutationFn: () =>
            api.refreshPreparation(p.id, p.revision, notice.data!.version),
        onSuccess: (next) => {
            saved(next);
            onRefreshed?.();
        },
    });
    if (
        !notice.data ||
        notice.data.guideState !== 'AVAILABLE' ||
        notice.data.version <= p.version
    ) {
        if (!showUnavailable || p.guideState === 'AVAILABLE') return null;
        return (
            <aside className={styles.guidanceCard}>
                <h2>
                    <Icon name="info" />
                    기록을 조회할 수 있어요
                </h2>
                <p>
                    기존 기록은 보관돼요. 새 준비 안내가 제공되면 다시 저장할 수
                    있어요.
                </p>
            </aside>
        );
    }
    return (
        <section className={styles.guidanceCard}>
            <h2>
                <Icon name="file" />
                준비 안내가 바뀌었어요
            </h2>
            {p.guideState !== 'AVAILABLE' && (
                <p>새 안내를 적용하면 다시 저장할 수 있어요.</p>
            )}
            <dl className={styles.guidanceFacts}>
                <div>
                    <dt>조건</dt>
                    <dd>이전 답변은 보관돼요. 바뀐 질문에 다시 답해 주세요.</dd>
                </div>
                <div>
                    <dt>서류</dt>
                    <dd>메모는 유지돼요. 완료한 서류는 다시 확인해 주세요.</dd>
                </div>
            </dl>
            <div className={styles.guidanceActions}>
                <Link to={'/notices/' + p.noticeId + '/versions'}>
                    수정 전후 비교
                </Link>
                <Button
                    disabled={refresh.isPending}
                    onClick={() => refresh.mutate()}
                >
                    {refresh.isPending ? '적용 중…' : '새 안내 적용하기'}
                </Button>
            </div>
            {refresh.error && <p role="alert">{refresh.error.message}</p>}
        </section>
    );
}
export function PreparationGuidance({ p }: { p: Preparation }) {
    const notice = useNotice(p.noticeId);
    return (
        <>
            <NoticeLifecycleNotice notice={notice.data} preparation />
            <GuidanceRefresh p={p} showUnavailable />
        </>
    );
}

export function PreparationNav({
    id,
    overviewIsList,
    followUpVisible = false,
}: {
    id: string;
    overviewIsList: boolean;
    followUpVisible?: boolean;
}) {
    const { state } = useLocation();
    return (
        <nav className={styles.tabs} aria-label="개인 준비">
            {preparationTabs
                .filter(
                    ([section]) => section !== 'follow-up' || followUpVisible,
                )
                .map(([section, label]) => (
                    <NavLink
                        key={section}
                        to={
                            !section && overviewIsList
                                ? '/preparations'
                                : preparationTabPath(id, section)
                        }
                        state={state?.detailReturn ? state : undefined}
                        end
                    >
                        {label}
                    </NavLink>
                ))}
        </nav>
    );
}
