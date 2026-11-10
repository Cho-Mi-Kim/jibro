import { useQuery } from '@tanstack/react-query';
import { useSession } from '../../app/useSession';
import { demoMode } from '../../shared/api/transport';
import { Button } from '../../shared/ui/ui';
import { api } from '../housing/api';
import { cx } from '../../shared/styles/classes';

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
            <Button className="secondary" onClick={onStart} disabled={pending}>
                가입 없이 체험하기
            </Button>
            <p>
                이메일 없이 예시 공고의 조건·서류·일정을 준비해 보세요. 체험
                기록은 종료 시 삭제되며 최대 24시간 동안 유지됩니다.
            </p>
        </div>
    );
}
