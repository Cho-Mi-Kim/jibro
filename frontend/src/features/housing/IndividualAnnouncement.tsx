import { ScheduleInfoDialog } from './ScheduleInfoDialog';
import type { Schedule } from './types';
import { EvidenceLink } from './EvidenceLink';
import styles from './IndividualAnnouncement.module.css';

export function IndividualAnnouncement({
    event,
}: {
    event: Omit<Schedule, 'recorded'>;
}) {
    return (
        <ScheduleInfoDialog
            label="개별 안내"
            title="결과 발표 · 개별 안내"
            buttonLabel="결과 발표 개별 안내 설명"
        >
            <p className={styles.description}>{event.timingDescription}</p>
            <EvidenceLink evidence={event.evidence} />
        </ScheduleInfoDialog>
    );
}
