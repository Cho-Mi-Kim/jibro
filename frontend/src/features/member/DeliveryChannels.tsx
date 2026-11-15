import { ForegroundControl } from './ForegroundAlerts';
import { Link } from 'react-router';
import { demoMode } from '../../shared/api/transport';
import { Button, State } from '../../shared/ui/ui';
import { useDeliveryChannels } from './useDeliveryChannels';
import s from './Controls.module.css';

export function DeliveryChannels() {
    const { channels, device, change, supported, enabled } =
        useDeliveryChannels();
    if (demoMode || !enabled) return null;
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
                            (supported && device.isPending) ||
                            (!device.data &&
                                (!supported || !channels.data.publicKey))
                        }
                        onClick={() =>
                            change.mutate(device.data ? 'remove' : 'subscribe')
                        }
                    >
                        {device.data
                            ? '이 기기 알림 구독 해제'
                            : '이 기기 푸시 구독'}
                    </Button>
                </>
            )}
            {device.error && (
                <State
                    error={device.error}
                    retry={() => void device.refetch()}
                />
            )}
            {change.error && <p role="alert">{change.error.message}</p>}
            {change.isSuccess && <p role="status">수신 방법을 반영했습니다.</p>}
        </section>
    );
}
