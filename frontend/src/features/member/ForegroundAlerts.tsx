import { useEffect, useState, useSyncExternalStore } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../../app/useSession';
import { memberApi } from './memberApi';
import { Button } from '../../shared/ui/ui';
import { demoMode } from '../../shared/api/transport';
const eventName = 'jibro.reminder.preferences';
function subscribe(callback: () => void) {
    window.addEventListener('storage', callback);
    window.addEventListener(eventName, callback);
    return () => {
        window.removeEventListener('storage', callback);
        window.removeEventListener(eventName, callback);
    };
}
function usePreference() {
    const { user } = useSession(),
        key = 'jibro.foreground.' + (user?.id ?? '');
    const enabled = useSyncExternalStore(subscribe, () => {
        try {
            return !!user && localStorage.getItem(key) === 'yes';
        } catch {
            return false;
        }
    });
    return { key, enabled, user };
}
export function ForegroundControl() {
    const { key, enabled } = usePreference();
    const [error, setError] = useState('');
    async function change() {
        try {
            if (
                !enabled &&
                (await Notification.requestPermission()) !== 'granted'
            )
                throw Error('이 사이트에 브라우저 알림 권한을 허용해 주세요.');
            localStorage.setItem(key, enabled ? 'no' : 'yes');
            window.dispatchEvent(new Event(eventName));
            setError('');
        } catch (e) {
            setError(
                e instanceof Error ? e.message : '기기 설정을 확인해 주세요.',
            );
        }
    }
    if (!('Notification' in window))
        return <p>현재 브라우저에서는 기기 알림을 지원하지 않습니다.</p>;
    return (
        <>
            <p>
                사이트를 열어 둔 동안 이 기기에 새 공고 소식을 표시할 수
                있습니다.
            </p>
            <Button className="secondary" onClick={() => void change()}>
                {enabled ? '열린 사이트 알림 끄기' : '열린 사이트 알림 켜기'}
            </Button>
            {error && <p role="alert">{error}</p>}
        </>
    );
}
export function ForegroundAlerts() {
    const { key, enabled, user } = usePreference();
    const active =
        enabled &&
        !demoMode &&
        'Notification' in window &&
        Notification.permission === 'granted';
    const inbox = useQuery({
        queryKey: ['alerts', user?.id],
        queryFn: memberApi.inbox,
        enabled: active,
        refetchInterval: active ? 60000 : false,
    });
    useEffect(() => {
        if (!active || !inbox.data?.settings.enabled) return;
        const unread = inbox.data.items.filter((i) => !i.read);
        if (!unread.length) return;
        try {
            let history: string[] = JSON.parse(
                localStorage.getItem(key + '.seen') ?? '[]',
            );
            if (!Array.isArray(history)) history = [];
            if (!unread.some((i) => !history.includes(i.key))) return;
            const nextSeen = JSON.stringify(
                [
                    ...new Set([...history, ...unread.map((item) => item.key)]),
                ].slice(-200),
            );
            const options = {
                body: '공고의 변경 사항과 기한을 알림함에서 확인하세요.',
                tag: 'jibro-foreground',
            };
            if ('serviceWorker' in navigator) {
                void navigator.serviceWorker
                    .register('/reminder-worker.js')
                    .then(() => navigator.serviceWorker.ready)
                    .then((reg) =>
                        reg.showNotification('집으로 · 새 공고 소식', options),
                    )
                    .then(() => localStorage.setItem(key + '.seen', nextSeen))
                    .catch(() => {
                        /* The inbox remains available when a device denies notification display. */
                    });
            } else {
                const notification = new Notification(
                    '집으로 · 새 공고 소식',
                    options,
                );
                localStorage.setItem(key + '.seen', nextSeen);
                notification.onclick = () => {
                    window.focus();
                    window.location.assign('/alerts');
                };
            }
        } catch {
            /* Site inbox still works when the browser blocks desktop notifications. */
        }
    }, [active, inbox.data, key]);
    return null;
}
