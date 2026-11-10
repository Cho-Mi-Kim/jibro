import { type ReactNode, useId } from 'react';
import { Link, useSearchParams } from 'react-router';
import type { Preparation } from '../housing/types';
import { useNotice } from '../../shared/ui/housingHelpers';
import { Disc, Journey, OfficialLink } from '../../shared/ui/HousingUI';
import { Icon } from '../../shared/ui/Icon';
import { State } from '../../shared/ui/ui';
import { PreparationGuidance } from './PreparationNav';
import { PreparationOverviewSummary } from './PreparationOverviewSummary';
import { ApplicationRecordDialog } from './ApplicationRecordDialog';
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
                    to={p.nextAction.path}
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
                            공식 신청
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
                    {notes}
                    <Link to={p.nextAction.path} className={styles.continue}>
                        준비 계속하기 <Icon name="arrow" />
                    </Link>
                </aside>
            </div>
        </>
    );
}
