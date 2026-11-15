import lhCi from '../../shared/assets/lh-ci.svg';
import styles from './AgencyMark.module.css';

export function AgencyMark({ agency }: { agency: string }) {
    if (agency.trim().toUpperCase() !== 'LH') return <>{agency}</>;

    return (
        <img
            className={styles.logo}
            src={lhCi}
            alt="LH"
            title="한국토지주택공사"
            width={42}
            height={28}
        />
    );
}
