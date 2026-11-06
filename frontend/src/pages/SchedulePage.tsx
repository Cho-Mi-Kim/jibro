import { ScheduleTimeline } from '../features/preparation/ScheduleTimeline';
import { ScheduleNextCard } from '../features/preparation/ScheduleNextCard';
import { useNotice } from '../shared/ui/housingHelpers';
import { cx } from '../shared/styles/classes';
import { useEffect } from 'react';
import { State } from '../shared/ui/ui';
import { NoticeContext } from '../shared/ui/HousingUI';
import { PreparationGuidance } from '../features/preparation/PreparationNav';
import { usePreparation } from '../features/preparation/usePreparation';
import { useLocation } from 'react-router';
export function SchedulePage() {
    const q = usePreparation();
    const p = q.data;
    const n = useNotice(p?.noticeId);
    const { hash } = useLocation();
    const loadedId = p?.id;
    useEffect(() => {
        if (!loadedId || hash !== '#timeline') return;
        const el = document.getElementById('timeline');
        const frame = requestAnimationFrame(() => {
            el?.scrollIntoView?.({ block: 'start' });
            el?.focus({ preventScroll: true });
        });
        return () => cancelAnimationFrame(frame);
    }, [loadedId, hash]);
    return (
        <>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <div className={cx('schedule-grid')}>
                    <div className={cx('schedule-main-column')}>
                        <div className={cx('schedule-context')}>
                            <NoticeContext p={p} />
                            <PreparationGuidance p={p} />
                        </div>
                        <ScheduleTimeline notice={n.data} preparation={p} />
                    </div>
                    <div className={cx('schedule-side-column')}>
                        <ScheduleNextCard notice={n.data} preparation={p} />
                    </div>
                </div>
            )}
        </>
    );
}
