import { Link, Navigate, useLocation } from 'react-router';
import { useEffect } from 'react';
import { useSession } from '../features/auth/useSession';
import { choosePreparation } from '../features/preparation/useActivePreparation';
import { usePreparation } from '../features/preparation/usePreparation';
import { useNotice } from '../features/housing/useNotice';
import { FollowUpSummary } from '../features/preparation/FollowUpSummary';
import { FollowUpEditor } from '../features/preparation/FollowUpEditor';
import { NoticeContext } from '../features/preparation/PreparationUI';
import { OfficialLink } from '../shared/ui/OfficialLink';
import { State } from '../shared/ui/ui';
import { Icon } from '../shared/ui/Icon';
import styles from '../features/preparation/FollowUp.module.css';
import { showFollowUp } from '../features/housing/followUp';

export function FollowUpPage() {
    const { state } = useLocation();
    const q = usePreparation();
    const p = q.data;
    const { user } = useSession();
    const preparationId = p?.id;
    useEffect(() => {
        if (user && preparationId) choosePreparation(user.id, preparationId);
    }, [user, preparationId]);
    const notice = useNotice(p?.noticeId);
    if (p && !showFollowUp(p))
        return <Navigate to={`/preparations/${p.id}`} replace state={state} />;
    return (
        <>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <div className={styles.page}>
                    <NoticeContext p={p} />
                    <FollowUpSummary record={p.followUp} />
                    <FollowUpEditor key={p.id} preparation={p} />
                    <div className={styles.resources}>
                        <OfficialLink
                            url={notice.data?.evidence.url}
                            className={styles.resourceLink}
                        >
                            <Icon name="file" />
                            <span>공식 결과·후속 안내 확인</span>
                        </OfficialLink>
                        <Link to="/archive" className={styles.resourceLink}>
                            <Icon name="bookmark" />
                            <span>준비 기록 보관하기</span>
                            <Icon name="arrow" />
                        </Link>
                    </div>
                </div>
            )}
        </>
    );
}
