import { applicationRecordSchedule } from '../housing/schedules';
import { applicationInterrupted } from '../housing/noticeLifecycle';
import { noticeAgency } from '../housing/noticeAgency';
import { type ReactNode } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import type { Preparation } from '../housing/types';
import { useNotice } from '../housing/useNotice';
import { Journey } from './PreparationUI';
import { OfficialLink } from '../../shared/ui/OfficialLink';
import { State } from '../../shared/ui/ui';
import { PreparationGuidance } from './PreparationNav';
import { PreparationOverviewSummary } from './PreparationOverviewSummary';
import { ApplicationRecordDialog } from './ApplicationRecordDialog';
import { PreparationDeleteDialog } from './PreparationDeleteDialog';
import { NextAction } from './NextAction';
import { FollowUpSummary } from './FollowUpSummary';
import { showFollowUp } from '../housing/followUp';
import styles from './Overview.module.css';

export function PreparationOverview({
    p,
    notes,
}: {
    p: Preparation;
    notes?: ReactNode;
}) {
    const notice = useNotice(p.noticeId);
    const { state } = useLocation();
    const [params, setParams] = useSearchParams();
    const applied = applicationRecordSchedule(p.schedules)?.recorded === true;
    const applicationButton = (
        <ApplicationRecordDialog
            key={p.id}
            preparation={p}
            requested={params.get('application') === 'record'}
            onDismiss={dismissApplication}
        />
    );
    function dismissApplication() {
        if (params.get('application') !== 'record') return;
        const next = new URLSearchParams(params);
        next.delete('application');
        setParams(next, { replace: true, state });
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
                <NextAction p={p} compact />
                {showFollowUp(p) && (
                    <div className={styles.followUp}>
                        <FollowUpSummary
                            record={p.followUp}
                            preparationId={p.id}
                        />
                    </div>
                )}
                <div className={styles.journey}>
                    <Journey p={p} />
                </div>
                <section className={styles.official} aria-label="바로가기">
                    <div
                        className={styles.officialActions}
                        data-applied={applied}
                    >
                        <OfficialLink
                            url={notice.data?.evidence.url}
                            className=""
                            showIcon={false}
                        >
                            {notice.data?.agency?.trim()
                                ? noticeAgency(notice.data.agency).name + ' '
                                : ''}
                            {applicationInterrupted(notice.data)
                                ? '공식 안내'
                                : '공식 신청'}
                        </OfficialLink>
                        {applicationButton}
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
