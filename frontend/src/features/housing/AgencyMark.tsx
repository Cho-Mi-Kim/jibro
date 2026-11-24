import { noticeAgency } from './noticeAgency';
import { noticeHousingSummary } from './noticeHousingFacts';
import type { Notice } from './types';
import styles from './AgencyMark.module.css';

export function AgencyMark({ agency }: { agency: string }) {
    const { name, fullName, logo } = noticeAgency(agency);
    if (!logo) return <>{name}</>;

    return (
        <img
            className={styles.logo}
            src={logo}
            alt={name}
            title={fullName ?? name}
            width={42}
            height={28}
        />
    );
}

export function AgencyHousingSummary({
    notice,
}: {
    notice: Pick<Notice, 'agency' | 'region' | 'type'>;
}) {
    return (
        <>
            <AgencyMark agency={notice.agency} /> ·{' '}
            {noticeHousingSummary(notice)}
        </>
    );
}
