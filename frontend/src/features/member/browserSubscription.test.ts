import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { subscribeBrowser } from './browserSubscription';

const publicKey = btoa(String.fromCharCode(4, 1, 2));
const permission = vi.fn();
const getSubscription = vi.fn();
const subscribe = vi.fn();
const register = vi.fn();

beforeEach(() => {
    vi.stubGlobal('isSecureContext', true);
    vi.stubGlobal('PushManager', class {});
    vi.stubGlobal('Notification', { requestPermission: permission });
    vi.stubGlobal('navigator', {
        serviceWorker: {
            register,
            ready: Promise.resolve({
                pushManager: { getSubscription, subscribe },
            }),
        },
    });
    permission.mockResolvedValue('granted');
    getSubscription.mockResolvedValue(null);
    register.mockResolvedValue(undefined);
});

afterEach(() => vi.unstubAllGlobals());

it('keeps an existing subscription only when its application key matches', async () => {
    const existing = {
        endpoint: 'https://fcm.googleapis.com/owned',
        options: { applicationServerKey: Uint8Array.of(4, 1, 2).buffer },
        unsubscribe: vi.fn(),
    };
    getSubscription.mockResolvedValue(existing);
    const result = await subscribeBrowser(publicKey);
    expect(result.subscription).toBe(existing);
    expect(result.replacedEndpoint).toBeNull();
    expect(existing.unsubscribe).not.toHaveBeenCalled();
    expect(subscribe).not.toHaveBeenCalled();
});

it('replaces an obsolete server key and returns the old endpoint for server removal', async () => {
    const existing = {
        endpoint: 'https://fcm.googleapis.com/old-key',
        options: { applicationServerKey: Uint8Array.of(4, 9, 9).buffer },
        unsubscribe: vi.fn().mockResolvedValue(true),
    };
    const replacement = { endpoint: 'https://fcm.googleapis.com/new-key' };
    getSubscription.mockResolvedValue(existing);
    subscribe.mockResolvedValue(replacement);
    const result = await subscribeBrowser(publicKey);
    expect(existing.unsubscribe).toHaveBeenCalledOnce();
    expect(subscribe).toHaveBeenCalledWith({
        userVisibleOnly: true,
        applicationServerKey: Uint8Array.of(4, 1, 2),
    });
    expect(result).toEqual({
        subscription: replacement,
        replacedEndpoint: existing.endpoint,
        created: true,
    });
});

it('does not create a subscription when permission is refused', async () => {
    permission.mockResolvedValue('denied');
    await expect(subscribeBrowser(publicKey)).rejects.toThrow('권한');
    expect(register).not.toHaveBeenCalled();
});
