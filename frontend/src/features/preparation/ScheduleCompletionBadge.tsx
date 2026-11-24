import { ApplicationCompletionBadge } from './ApplicationCompletionBadge';
import { Icon } from '../../shared/ui/Icon';
import { Status } from '../../shared/ui/Status';

const completion = {
    recorded: { label: '완료로 기록함', icon: 'check', tone: 'green' },
    unrecorded: { label: '완료 기록 없음', icon: 'clock', tone: 'gray' },
} as const;

export function ScheduleCompletionBadge({
    recorded,
    application = false,
}: {
    recorded: boolean;
    application?: boolean;
}) {
    if (application) return <ApplicationCompletionBadge recorded={recorded} />;
    const { label, icon, tone } = recorded
        ? completion.recorded
        : completion.unrecorded;
    return (
        <Status tone={tone}>
            <Icon name={icon} />
            {label}
        </Status>
    );
}
