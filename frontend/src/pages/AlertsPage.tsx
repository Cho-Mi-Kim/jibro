import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../features/auth/useSession';
import { noticeListTitle } from '../features/housing/noticeDisplay';
import { memberApi } from '../features/member/memberApi';
import { Button, State } from '../shared/ui/ui';
import s from '../features/member/Controls.module.css';

const alertDateFormat = new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    dateStyle: 'medium',
});

function formatAlertTime(timestamp: number, now: number) {
    const minutes = Math.floor((now - timestamp) / 60_000);
    if (minutes < 1) return '방금';
    if (minutes < 60) return `${minutes}분 전`;
    if (minutes < 24 * 60) return `${Math.floor(minutes / 60)}시간 전`;
    return alertDateFormat.format(timestamp);
}

function AlertTime({ value, now }: { value: string | null; now: number }) {
    if (!value) return null;
    const timestamp = Date.parse(value);
    if (!Number.isFinite(timestamp)) return null;
    return (
        <time className={s.alertTime} dateTime={value}>
            {formatAlertTime(timestamp, now)}
        </time>
    );
}

export function AlertsPage() {
    const [now, setNow] = useState(Date.now);
    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 60_000);
        return () => window.clearInterval(timer);
    }, []);
    const { user } = useSession(),
        client = useQueryClient(),
        key = ['alerts', user?.id];
    const inbox = useQuery({
        queryKey: key,
        queryFn: memberApi.inbox,
        staleTime: 60_000,
    });
    const refresh = () => client.invalidateQueries({ queryKey: key });
    const read = useMutation({
        mutationFn: memberApi.read,
        onSuccess: refresh,
    });
    const items = inbox.data?.items ?? [];
    const unread = items.filter((item) => !item.read);
    const orderedItems = unread.concat(items.filter((item) => item.read));
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
                            {orderedItems.map((i) => (
                                <li key={i.key} className={s.alertRow}>
                                    <Link
                                        className={s.alertLink}
                                        to={
                                            '/notices/' +
                                            encodeURIComponent(i.noticeId)
                                        }
                                        onClick={() => {
                                            if (!i.read) read.mutate([i.key]);
                                        }}
                                        onAuxClick={(event) => {
                                            if (event.button === 1 && !i.read)
                                                read.mutate([i.key]);
                                        }}
                                    >
                                        {!i.read && (
                                            <span
                                                className={s.unreadDot}
                                                role="img"
                                                aria-label="읽지 않음"
                                            />
                                        )}
                                        <div className={s.alertHeading}>
                                            <strong>
                                                {i.label} {i.date}
                                            </strong>
                                            <AlertTime
                                                value={i.occurredAt}
                                                now={now}
                                            />
                                        </div>
                                        <span
                                            className={s.alertTitle}
                                            title={i.title}
                                        >
                                            {noticeListTitle(i.title)}
                                        </span>
                                    </Link>
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
