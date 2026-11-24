import { useEffect, useRef } from 'react';

type Turnstile = {
    render: (
        element: HTMLElement,
        options: {
            sitekey: string;
            action: string;
            language: string;
            size: string;
            theme: string;
            callback: (token: string) => void;
            'error-callback': () => void;
            'expired-callback': () => void;
            'timeout-callback': () => void;
        },
    ) => string;
    remove: (id: string) => void;
};
declare global {
    interface Window {
        turnstile?: Turnstile;
    }
}
let loading: Promise<Turnstile> | undefined;
function loadTurnstile() {
    if (window.turnstile) return Promise.resolve(window.turnstile);
    loading ??= new Promise<Turnstile>((resolve, reject) => {
        const script = document.createElement('script');
        script.src =
            'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
        script.async = true;
        const fail = () => {
            clearTimeout(timeout);
            script.remove();
            loading = undefined;
            reject(new Error('Cloudflare 확인 화면을 불러오지 못했어요.'));
        };
        const timeout = setTimeout(fail, 15000);
        script.onerror = fail;
        script.onload = () => {
            clearTimeout(timeout);
            if (window.turnstile) resolve(window.turnstile);
            else fail();
        };
        document.head.append(script);
    });
    return loading;
}

export function TurnstileWidget({
    siteKey,
    onToken,
    onError,
}: {
    siteKey: string;
    onToken: (token: string) => void;
    onError: () => void;
}) {
    const container = useRef<HTMLDivElement>(null);
    useEffect(() => {
        let cancelled = false;
        let widget: string | undefined;
        let api: Turnstile | undefined;
        void loadTurnstile()
            .then((turnstile) => {
                if (cancelled || !container.current) return;
                api = turnstile;
                widget = api.render(container.current, {
                    sitekey: siteKey,
                    action: 'site_entry',
                    language: 'ko',
                    size: 'flexible',
                    theme: 'auto',
                    callback: (token) => {
                        if (!cancelled) onToken(token);
                    },
                    'error-callback': () => {
                        if (!cancelled) onError();
                    },
                    'expired-callback': () => {
                        if (!cancelled) onError();
                    },
                    'timeout-callback': () => {
                        if (!cancelled) onError();
                    },
                });
            })
            .catch(() => {
                if (!cancelled) onError();
            });
        return () => {
            cancelled = true;
            if (widget !== undefined) api?.remove(widget);
        };
    }, [siteKey, onToken, onError]);
    return <div ref={container} aria-label="Cloudflare 사람 확인" />;
}
