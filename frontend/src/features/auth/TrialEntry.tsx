import { useQuery } from '@tanstack/react-query';
import { useSession } from './useSession';
import { demoMode } from '../../shared/api/transport';
import { Button } from '../../shared/ui/ui';
import { api } from '../housing/api';
import { bindClasses } from '../../shared/styles/classes';
import authClasses from './Auth.module.css';

const cx = bindClasses(authClasses);

export function TrialEntry({
    onStart,
    pending,
}: {
    onStart: () => void;
    pending: boolean;
}) {
    const { user, loading } = useSession();
    const meta = useQuery({
        queryKey: ['meta'],
        queryFn: api.meta,
        enabled: !demoMode,
        staleTime: Infinity,
    });
    if (user || loading || demoMode || !meta.data?.trialAvailable) return null;
    return (
        <div className={cx('trial-entry')}>
            <Button
                type="button"
                className="secondary"
                onClick={onStart}
                disabled={pending}
            >
                가입 없이 체험하기
            </Button>
        </div>
    );
}
