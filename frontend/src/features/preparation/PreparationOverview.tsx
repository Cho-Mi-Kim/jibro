import { applicationInterrupted } from '../housing/noticeLifecycle';
import { type ReactNode, useId } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { Preparation } from '../housing/types';
import { useNotice } from '../housing/useNotice';
import { Disc } from '../../shared/ui/Disc';
import { Journey } from './PreparationUI';
import { OfficialLink } from '../../shared/ui/OfficialLink';
import { Icon } from '../../shared/ui/Icon';
import { State } from '../../shared/ui/ui';
import { PreparationGuidance } from './PreparationNav';
import { PreparationOverviewSummary } from './PreparationOverviewSummary';
import { ApplicationRecordDialog } from './ApplicationRecordDialog';
import { PreparationDeleteDialog } from './PreparationDeleteDialog';
import styles from './Overview.module.css';

export function PreparationOverview({
    p,
    notes,
}: {
    p: Preparation;
    notes?: ReactNode;
}) {
    const notice = useNotice(p.noticeId);
    const reasonId = useId();
    const [params, setParams] = useSearchParams();
    function dismissApplication() {
        if (params.get('application') !== 'record') return;
        const next = new URLSearchParams(params);
        next.delete('application');
        setParams(next, { replace: true });
    }
    return (
        <>
            <PreparationGuidance p={p} />
            <div className={styles.overview}>
                <div className={styles.summary}>
                    <State
                        error={notice.error}
                        retry={() => void notice.refetch()}
                    />
                    <PreparationOverviewSummary
                        preparation={p}
                        notice={notice.data}
                    />
                </div>
                <Link
                    className={styles.next}
                    to={
                        p.nextAction.label === '확인하지 못한 조건 살펴보기'
                            ? `${p.nextAction.path}?review=remaining`
                            : p.nextAction.path
                    }
                    aria-describedby={reasonId}
                >
                    <Disc name="warning" tone="amber" />
                    <div>
                        <span>지금 할 일</span>
                        <h2>{p.nextAction.label}</h2>
                    </div>
                    <Icon name="arrow" />
                    <span id={reasonId} className={styles.reason}>
                        {p.nextAction.reason}
                    </span>
                </Link>
                <div className={styles.journey}>
                    <Journey p={p} />
                </div>
                <section className={styles.official} aria-label="바로가기">
                    <div className={styles.officialActions}>
                        <OfficialLink
                            url={notice.data?.evidence.url}
                            className=""
                            showIcon={false}
                        >
                            {notice.data?.agency
                                ? notice.data.agency + ' '
                                : ''}
                            {applicationInterrupted(notice.data)
                                ? '공식 안내'
                                : '공식 신청'}
                        </OfficialLink>
                        <ApplicationRecordDialog
                            key={p.id}
                            preparation={p}
                            requested={params.get('application') === 'record'}
                            onDismiss={dismissApplication}
                        />
                    </div>
                </section>
                <aside className={styles.resume}>
                    <div className={styles.recordActions}>
                        <Link to={`/preparations/${p.id}/history`}>
                            이전 준비 기록 보기
                        </Link>
                        <PreparationDeleteDialog
                            id={p.id}
                            afterDeleteTo="/preparations"
                        />
                    </div>
                    {notes}
                </aside>
            </div>
        </>
    );
}
