import { call, demoMode } from '../../shared/api/transport';
export async function disconnectThisDevice() {
    if (demoMode || !('serviceWorker' in navigator)) return;
    try {
        const reg = await navigator.serviceWorker.getRegistration();
        if (!reg?.active?.scriptURL.endsWith('/reminder-worker.js')) return;
        const subscription = await reg.pushManager.getSubscription();
        if (!subscription) return;
        try {
            await call('/member/channels/push/remove', 'POST', {
                endpoint: subscription.endpoint,
            });
        } finally {
            await subscription.unsubscribe();
        }
    } catch {
        /* Logout still revokes the member session if the device or push service is offline. */
    }
}
