import {
    useEffect,
    useLayoutEffect,
    useState,
    useSyncExternalStore,
} from 'react';
import { useLocation } from 'react-router';
import {
    AnalyticsClient,
    analyticsSettingsEvent,
    readAnalyticsConsent,
    saveAnalyticsConsent,
    subscribeAnalyticsConsent,
    type AnalyticsConsentValue,
} from './analytics';
import styles from './AnalyticsConsent.module.css';

const productionClient = new AnalyticsClient(
    import.meta.env.VITE_GA_MEASUREMENT_ID ?? '',
    window.location.origin,
    import.meta.env.VITE_GA_TAG_ID ?? '',
);

export function AnalyticsConsent({
    client = productionClient,
}: {
    client?: AnalyticsClient;
}) {
    const location = useLocation();
    const consent = useSyncExternalStore(
        subscribeAnalyticsConsent,
        readAnalyticsConsent,
        () => null,
    );
    const [settingsOpen, setSettingsOpen] = useState(
        location.hash === '#analytics-settings',
    );
    useEffect(() => {
        const open = () => setSettingsOpen(true);
        window.addEventListener(analyticsSettingsEvent, open);
        return () => window.removeEventListener(analyticsSettingsEvent, open);
    }, []);
    useLayoutEffect(() => {
        client.update(consent, location.pathname);
    }, [client, consent, location.pathname]);
    const choose = (value: AnalyticsConsentValue) => {
        client.update(value, location.pathname);
        saveAnalyticsConsent(value);
        setSettingsOpen(false);
    };
    if (!settingsOpen && (!client.enabled || consent !== null)) return null;
    return (
        <section className={styles.panel} aria-label="방문 통계 설정">
            <div>
                <h2>방문 통계 설정</h2>
                {client.enabled ? (
                    <p>
                        화면 이용 통계를 허용해 서비스 개선에 도움을 줄 수
                        있어요. 답변·메모·검색어는 보내지 않아요. 거절해도 모든
                        기능을 이용할 수 있어요.
                    </p>
                ) : (
                    <p>현재 방문 통계 수집을 사용하지 않아요.</p>
                )}
                <a href="/privacy">개인정보처리방침 보기</a>
            </div>
            <div className={styles.actions}>
                {client.enabled ? (
                    <>
                        <button type="button" onClick={() => choose('denied')}>
                            필수 기능만
                        </button>
                        <button type="button" onClick={() => choose('granted')}>
                            통계 허용
                        </button>
                    </>
                ) : (
                    <button
                        type="button"
                        onClick={() => setSettingsOpen(false)}
                    >
                        닫기
                    </button>
                )}
            </div>
        </section>
    );
}
