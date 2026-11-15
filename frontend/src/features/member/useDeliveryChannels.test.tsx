import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Session } from '../auth/useSession';
import { useDeliveryChannels } from './useDeliveryChannels';
import { deliveryChannelsApi as api } from './deliveryChannelsApi';
import * as browser from './browserSubscription';

beforeEach(() => {
    vi.spyOn(browser, 'supportsBrowserPush').mockReturnValue(true);
    vi.spyOn(browser, 'currentBrowserSubscription').mockResolvedValue(null);
    vi.spyOn(api, 'settings').mockResolvedValue({
        emailEnabled: false,
        emailVerified: true,
        mailMode: 'smtp',
        publicKey: 'public-key',
    });
    vi.spyOn(api, 'ownsDevice').mockResolvedValue(false);
    vi.spyOn(api, 'subscribe').mockResolvedValue();
});

afterEach(() => vi.restoreAllMocks());

function setup() {
    let owner: string | null = 'owner-a';
    const client = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
        },
    });
    const view = renderHook(() => useDeliveryChannels(), {
        wrapper: ({ children }: { children: ReactNode }) => (
            <QueryClientProvider client={client}>
                <Session.Provider
                    value={{
                        user: owner
                            ? {
                                  id: owner,
                                  name: '회원',
                                  email: 'test@example.invalid',
                                  role: 'USER',
                              }
                            : null,
                        loading: false,
                        error: null,
                        refresh: async () => {},
                    }}
                >
                    {children}
                </Session.Provider>
            </QueryClientProvider>
        ),
    });
    return {
        ...view,
        client,
        changeOwner: (next: string | null) => {
            owner = next;
            view.rerender();
        },
    };
}

function waitingSubscription(created = true) {
    const subscription = {
        endpoint: 'https://fcm.googleapis.com/test-device',
        unsubscribe: vi.fn().mockResolvedValue(true),
    } as unknown as PushSubscription;
    let complete!: (
        value: Awaited<ReturnType<typeof browser.subscribeBrowser>>,
    ) => void;
    const pending = new Promise<
        Awaited<ReturnType<typeof browser.subscribeBrowser>>
    >((resolve) => {
        complete = resolve;
    });
    vi.spyOn(browser, 'subscribeBrowser').mockReturnValue(pending);
    return {
        subscription,
        release: () =>
            complete({ subscription, replacedEndpoint: null, created }),
    };
}

it('does not register after an owner change and invalidates only the initiating owner', async () => {
    const pending = waitingSubscription();
    const view = setup();
    const invalidate = vi.spyOn(view.client, 'invalidateQueries');
    await waitFor(() =>
        expect(view.result.current.channels.data?.publicKey).toBe('public-key'),
    );
    let mutation!: Promise<unknown>;
    act(() => {
        mutation = view.result.current.change
            .mutateAsync('subscribe')
            .catch((error: Error) => error);
    });
    await waitFor(() => expect(browser.subscribeBrowser).toHaveBeenCalled());
    view.changeOwner('owner-b');
    await act(async () => {
        pending.release();
        await mutation;
    });
    expect(api.subscribe).not.toHaveBeenCalled();
    expect(pending.subscription.unsubscribe).toHaveBeenCalledOnce();
    expect(invalidate).toHaveBeenCalledWith({
        queryKey: ['channels', 'owner-a'],
    });
    expect(invalidate).not.toHaveBeenCalledWith({
        queryKey: ['channels', 'owner-b'],
    });
});

it('does not unsubscribe an existing browser subscription after unmount', async () => {
    const pending = waitingSubscription(false);
    const view = setup();
    await waitFor(() =>
        expect(view.result.current.channels.data?.publicKey).toBe('public-key'),
    );
    let mutation!: Promise<unknown>;
    act(() => {
        mutation = view.result.current.change
            .mutateAsync('subscribe')
            .catch((error: Error) => error);
    });
    await waitFor(() => expect(browser.subscribeBrowser).toHaveBeenCalled());
    view.unmount();
    await act(async () => {
        pending.release();
        await mutation;
    });
    expect(api.subscribe).not.toHaveBeenCalled();
    expect(pending.subscription.unsubscribe).not.toHaveBeenCalled();
});

it('keeps a newly created subscription already claimed by the current signed-in owner', async () => {
    const pending = waitingSubscription();
    vi.mocked(api.ownsDevice).mockResolvedValue(true);
    const view = setup();
    await waitFor(() =>
        expect(view.result.current.channels.data?.publicKey).toBe('public-key'),
    );
    let mutation!: Promise<unknown>;
    act(() => {
        mutation = view.result.current.change
            .mutateAsync('subscribe')
            .catch((error: Error) => error);
    });
    await waitFor(() => expect(browser.subscribeBrowser).toHaveBeenCalled());
    view.changeOwner('owner-b');
    await act(async () => {
        pending.release();
        await mutation;
    });
    expect(api.subscribe).not.toHaveBeenCalled();
    expect(pending.subscription.unsubscribe).not.toHaveBeenCalled();
});

it('binds a completed subscription request to its initiating owner', async () => {
    const pending = waitingSubscription();
    const view = setup();
    await waitFor(() =>
        expect(view.result.current.channels.data?.publicKey).toBe('public-key'),
    );
    let mutation!: Promise<unknown>;
    act(() => {
        mutation = view.result.current.change.mutateAsync('subscribe');
    });
    await waitFor(() => expect(browser.subscribeBrowser).toHaveBeenCalled());
    await act(async () => {
        pending.release();
        await mutation;
    });
    expect(api.subscribe).toHaveBeenCalledWith(
        pending.subscription.endpoint,
        'owner-a',
    );
    expect(pending.subscription.unsubscribe).not.toHaveBeenCalled();
});

it('preserves a server-registered subscription when the owner changes before its response', async () => {
    const pending = waitingSubscription();
    let finishRegistration!: () => void;
    vi.mocked(api.subscribe).mockReturnValue(
        new Promise<void>((resolve) => {
            finishRegistration = resolve;
        }),
    );
    const view = setup();
    const invalidate = vi.spyOn(view.client, 'invalidateQueries');
    await waitFor(() =>
        expect(view.result.current.channels.data?.publicKey).toBe('public-key'),
    );
    let mutation!: Promise<unknown>;
    act(() => {
        mutation = view.result.current.change
            .mutateAsync('subscribe')
            .catch((error: Error) => error);
        pending.release();
    });
    await waitFor(() => expect(api.subscribe).toHaveBeenCalled());
    view.changeOwner('owner-b');
    await act(async () => {
        finishRegistration();
        await mutation;
    });
    expect(pending.subscription.unsubscribe).not.toHaveBeenCalled();
    expect(invalidate).toHaveBeenCalledWith({
        queryKey: ['channels', 'owner-a'],
    });
    expect(invalidate).not.toHaveBeenCalledWith({
        queryKey: ['channels', 'owner-b'],
    });
});
