import { ForegroundControl } from './ForegroundAlerts';
import { useEffect, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { useSession } from '../../app/useSession';
import { call, demoMode } from '../../shared/api/transport';
import { Button, State } from '../../shared/ui/ui';
import s from './Controls.module.css';
interface Channels {
    emailEnabled: boolean;
    emailVerified: boolean;
    mailMode: string;
    publicKey: string | null;
}
export function DeliveryChannels() {
    const { user } = useSession(),
        client = useQueryClient(),
        key = ['channels', user?.id];
    const channels = useQuery({
        queryKey: key,
        queryFn: () => call<Channels>('/member/channels'),
        enabled: !demoMode,
    });
    const [device, setDevice] = useState<PushSubscription | null>(null);
    const supported =
        'serviceWorker' in navigator &&
        'PushManager' in window &&
        'Notification' in window;
    useEffect(() => {
        let active = true;
        if (supported)
            void navigator.serviceWorker
                .getRegistration()
                .then((reg) => reg?.pushManager.getSubscription())
                .then(async (sub) => {
                    const owned =
                        sub &&
                        (await call<boolean>(
                            '/member/channels/push/status',
                            'POST',
                            { endpoint: sub.endpoint },
                        ));
                    if (active) setDevice(owned ? sub : null);
                })
                .catch(() => {});
        return () => {
            active = false;
        };
    }, [supported, user?.id]);
    const change = useMutation({
        mutationFn: async (action: 'email' | 'subscribe' | 'remove') => {
            if (action === 'email') {
                await call('/member/channels/email', 'PUT', {
                    enabled: !channels.data?.emailEnabled,
                });
                return;
            }
            if (action === 'remove' && device) {
                await call('/member/channels/push/remove', 'POST', {
                    endpoint: device.endpoint,
                });
                await device.unsubscribe();
                setDevice(null);
                return;
            }
            if (!channels.data?.publicKey || !supported)
                throw new Error('브라우저 푸시를 사용할 수 없습니다.');
            if ((await Notification.requestPermission()) !== 'granted')
                throw new Error('브라우저 알림 권한을 허용해야 합니다.');
            await navigator.serviceWorker.register('/reminder-worker.js');
            const reg = await navigator.serviceWorker.ready;
            const base = channels.data.publicKey
                .replaceAll('-', '+')
                .replaceAll('_', '/');
            const bytes = Uint8Array.from(atob(base), (c) => c.charCodeAt(0));
            const sub =
                (await reg.pushManager.getSubscription()) ??
                (await reg.pushManager.subscribe({
                    userVisibleOnly: true,
                    applicationServerKey: bytes,
                }));
            await call('/member/channels/push', 'PUT', {
                endpoint: sub.endpoint,
            });
            setDevice(sub);
        },
        onSuccess: () => client.invalidateQueries({ queryKey: key }),
    });
    if (demoMode) return null;
    return (
        <section className={s.section}>
            <h2>알림 수신 방법</h2>
            <ForegroundControl />
            <p>
                위에서 공고 변경 알림을 켜고 수신 방법을 선택하세요. 서버가 실행
                중일 때 예정된 기한과 변경 소식을 보냅니다.
            </p>
            <State
                loading={channels.isPending}
                error={channels.error}
                retry={() => void channels.refetch()}
            />
            {channels.data && (
                <>
                    <p>
                        {channels.data.mailMode === 'local'
                            ? '이메일은 로컬 테스트 메일함에 저장됩니다.'
                            : channels.data.mailMode === 'smtp'
                              ? '확인한 이메일로 발송할 수 있습니다.'
                              : '메일 서버 설정이 필요합니다.'}
                    </p>
                    <Button
                        className="secondary"
                        disabled={
                            change.isPending ||
                            (!channels.data.emailEnabled &&
                                (!channels.data.emailVerified ||
                                    channels.data.mailMode === 'unavailable'))
                        }
                        onClick={() => change.mutate('email')}
                    >
                        {channels.data.emailEnabled
                            ? '이메일 알림 끄기'
                            : '이메일 알림 켜기'}
                    </Button>
                    {!channels.data.emailVerified && (
                        <Link className={s.link} to="/settings">
                            이메일 확인하기
                        </Link>
                    )}
                    <p>
                        {channels.data.publicKey
                            ? supported
                                ? '이 브라우저에서만 수신합니다.'
                                : '이 브라우저에서는 푸시를 지원하지 않습니다.'
                            : '외부 브라우저 푸시는 서버 VAPID 키와 연락처 설정이 필요합니다.'}
                    </p>
                    <Button
                        className="secondary"
                        disabled={
                            change.isPending ||
                            (!device &&
                                (!supported || !channels.data.publicKey))
                        }
                        onClick={() =>
                            change.mutate(device ? 'remove' : 'subscribe')
                        }
                    >
                        {device
                            ? '이 기기 알림 구독 해제'
                            : '이 기기 푸시 구독'}
                    </Button>
                </>
            )}
            {change.error && <p role="alert">{change.error.message}</p>}
            {change.isSuccess && <p role="status">수신 방법을 반영했습니다.</p>}
        </section>
    );
}
