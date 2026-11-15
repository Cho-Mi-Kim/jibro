export function supportsBrowserPush() {
    return (
        window.isSecureContext &&
        'serviceWorker' in navigator &&
        'PushManager' in window &&
        'Notification' in window
    );
}

export async function currentBrowserSubscription() {
    const registration = await navigator.serviceWorker.getRegistration('/');
    return (await registration?.pushManager.getSubscription()) ?? null;
}

function subscriptionKey(value: string) {
    const base = value.replaceAll('-', '+').replaceAll('_', '/');
    return Uint8Array.from(atob(base), (character) => character.charCodeAt(0));
}

export function matchesSubscriptionKey(
    subscription: PushSubscription,
    key: Uint8Array,
) {
    const saved = subscription.options.applicationServerKey;
    if (!saved) return false;
    const bytes = new Uint8Array(saved);
    return (
        bytes.length === key.length && bytes.every((byte, i) => byte === key[i])
    );
}

export async function subscribeBrowser(
    publicKey: string,
    assertCurrent: () => void = () => {},
) {
    if (!supportsBrowserPush())
        throw new Error('이 브라우저에서는 푸시를 사용할 수 없습니다.');
    if ((await Notification.requestPermission()) !== 'granted')
        throw new Error('브라우저 알림 권한을 허용해야 합니다.');
    assertCurrent();
    await navigator.serviceWorker.register('/reminder-worker.js');
    assertCurrent();
    const registration = await navigator.serviceWorker.ready;
    assertCurrent();
    const key = subscriptionKey(publicKey);
    let existing = await registration.pushManager.getSubscription();
    assertCurrent();
    let replacedEndpoint: string | null = null;
    if (existing && !matchesSubscriptionKey(existing, key)) {
        replacedEndpoint = existing.endpoint;
        await existing.unsubscribe();
        assertCurrent();
        existing = null;
    }
    const subscription =
        existing ??
        (await registration.pushManager.subscribe({
            userVisibleOnly: true,
            applicationServerKey: key,
        }));
    return { subscription, replacedEndpoint, created: existing === null };
}
