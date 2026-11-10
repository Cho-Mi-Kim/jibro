import { Link } from 'react-router';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../app/useSession';
import { memberApi } from '../features/member/memberApi';
import { Button, State } from '../shared/ui/ui';
import s from '../features/member/Controls.module.css';

export function AlertsPage() {
    const { user } = useSession(),
        client = useQueryClient(),
        key = ['alerts', user?.id];
    const inbox = useQuery({
        queryKey: key,
        queryFn: memberApi.inbox,
        refetchInterval: 60000,
    });
    const refresh = () => client.invalidateQueries({ queryKey: key });
    const read = useMutation({
        mutationFn: memberApi.read,
        onSuccess: refresh,
    });
    const unread = inbox.data?.items.filter((i) => !i.read) ?? [];
    return (
        <>
            <State
                loading={inbox.isPending}
                error={inbox.error}
                retry={() => void inbox.refetch()}
            />
            {inbox.data && (
                <>
                    <section className={s.section}>
                        <h2>공고 소식 · 읽지 않음 {unread.length}건</h2>
                        {unread.length > 0 && (
                            <Button
                                disabled={read.isPending}
                                onClick={() =>
                                    read.mutate(unread.map((i) => i.key))
                                }
                            >
                                모두 읽음
                            </Button>
                        )}
                        {!inbox.data.items.length && (
                            <p>
                                {inbox.data.settings.enabled
                                    ? '현재 표시할 공고 소식이 없습니다.'
                                    : '오른쪽 위 알림 설정에서 알림을 켜 주세요.'}
                            </p>
                        )}
                        <ul className={s.list}>
                            {inbox.data.items.map((i) => (
                                <li key={i.key}>
                                    <strong>
                                        {i.label} {i.date}
                                    </strong>
                                    <p>
                                        <Link to={'/notices/' + i.noticeId}>
                                            {i.title}
                                        </Link>
                                    </p>
                                    {i.read ? (
                                        <small>읽음</small>
                                    ) : (
                                        <Button
                                            disabled={read.isPending}
                                            onClick={() => read.mutate([i.key])}
                                        >
                                            읽음 표시
                                        </Button>
                                    )}
                                </li>
                            ))}
                        </ul>
                    </section>
                </>
            )}
            {read.error && <p role="alert">{read.error.message}</p>}
        </>
    );
}
