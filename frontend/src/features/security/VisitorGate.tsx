import {
    useCallback,
    useEffect,
    useRef,
    useState,
    type ReactNode,
} from 'react';
import { call, demoMode } from '../../shared/api/transport';
import { visitorVerificationRequiredEvent } from '../../shared/api/client';
import { Icon } from '../../shared/ui/Icon';
import { Button } from '../../shared/ui/ui';
import { TurnstileWidget } from './TurnstileWidget';
import styles from './VisitorGate.module.css';

type Status = { enabled: boolean; verified: boolean; siteKey: string };

export function VisitorGate({ children }: { children: ReactNode }) {
    const [admitted, setAdmitted] = useState(demoMode);
    const [status, setStatus] = useState<Status>();
    const [challenge, setChallenge] = useState(!demoMode);
    const [message, setMessage] = useState('');
    const [attempt, setAttempt] = useState(0);
    const [busy, setBusy] = useState(false);
    const pending = useRef(false);
    const checking = useRef(!demoMode);
    const failed = useCallback(() => {
        setMessage('사람 확인을 완료하지 못했어요. 다시 시도해 주세요.');
    }, []);
    const verify = useCallback((token: string) => {
        if (pending.current) return;
        pending.current = true;
        setBusy(true);
        setMessage('확인 결과를 확인하고 있어요.');
        void call('/security/visitor', 'POST', { token })
            .then(() => {
                // Confirm the HttpOnly pass was accepted; blocked cookies must not loop.
                return call<Status>('/security/visitor');
            })
            .then((result) => {
                if (!result.verified)
                    throw new Error('쿠키를 허용한 뒤 다시 시도해 주세요.');
                setAdmitted(true);
                checking.current = false;
                setChallenge(false);
                setMessage('');
            })
            .catch((error) => {
                setMessage(
                    error instanceof Error
                        ? error.message
                        : '다시 시도해 주세요.',
                );
            })
            .finally(() => {
                pending.current = false;
                setBusy(false);
            });
    }, []);
    useEffect(() => {
        if (demoMode) return;
        let active = true;
        void call<Status>('/security/visitor')
            .then((result) => {
                if (!active) return;
                setStatus(result);
                setMessage('');
                if (result.verified) {
                    setAdmitted(true);
                    checking.current = false;
                    setChallenge(false);
                }
            })
            .catch(() => {
                if (active)
                    setMessage(
                        '사이트 보안 설정을 불러오지 못했어요. 다시 시도해 주세요.',
                    );
            });
        return () => {
            active = false;
        };
    }, [attempt]);
    useEffect(() => {
        const requireCheck = () => {
            if (checking.current) return;
            checking.current = true;
            setChallenge(true);
            setMessage('안전한 이용을 위해 다시 확인해 주세요.');
            setAttempt((value) => value + 1);
        };
        window.addEventListener(visitorVerificationRequiredEvent, requireCheck);
        return () =>
            window.removeEventListener(
                visitorVerificationRequiredEvent,
                requireCheck,
            );
    }, []);
    return (
        <>
            {admitted && <div hidden={challenge}>{children}</div>}
            {challenge && (
                <main className={styles.page}>
                    <section
                        className={styles.card}
                        aria-labelledby="visitor-title"
                    >
                        <div className={styles.brand}>
                            <Icon name="home" />
                            <strong>집으로</strong>
                        </div>
                        <h1 id="visitor-title">
                            안전한 접속을 확인하고 있어요
                        </h1>
                        <p>
                            아래의 사람 확인을 완료하면 바로 이어서 이용할 수
                            있어요.
                        </p>
                        {status?.enabled && (
                            <TurnstileWidget
                                key={attempt}
                                siteKey={status.siteKey}
                                onToken={verify}
                                onError={failed}
                            />
                        )}
                        <p role="status" className={styles.status}>
                            {message ||
                                (!status && '보안 확인을 준비하고 있어요.')}
                        </p>
                        {message && !busy && (
                            <Button
                                className="secondary"
                                onClick={() => setAttempt((value) => value + 1)}
                            >
                                다시 시도
                            </Button>
                        )}
                        <a href="/privacy">개인정보처리방침</a>
                    </section>
                </main>
            )}
        </>
    );
}
