import { Icon } from '../../shared/ui/Icon';
import { Status } from '../../shared/ui/Status';

const applicationStatus = {
    recorded: { label: '신청 완료로 기록함', icon: 'check', tone: 'green' },
    unrecorded: { label: '신청 완료 기록 없음', icon: 'clock', tone: 'gray' },
} as const;

export function ApplicationCompletionBadge({
    recorded,
}: {
    recorded?: boolean;
}) {
    if (recorded === undefined) return null;
    const { label, icon, tone } = recorded
        ? applicationStatus.recorded
        : applicationStatus.unrecorded;
    return (
        <Status tone={tone}>
            <Icon name={icon} />
            {label}
        </Status>
    );
}
