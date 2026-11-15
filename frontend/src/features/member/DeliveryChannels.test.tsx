import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { Session } from '../auth/useSession';
import { DeliveryChannels } from './DeliveryChannels';
import { deliveryChannelsApi as api } from './deliveryChannelsApi';
import * as browser from './browserSubscription';

beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
    vi.spyOn(browser, 'supportsBrowserPush').mockReturnValue(false);
    vi.spyOn(api, 'settings').mockResolvedValue({
        emailEnabled: false,
        emailVerified: true,
        mailMode: 'smtp',
        publicKey: null,
    });
    vi.spyOn(api, 'email').mockResolvedValue();
    vi.spyOn(api, 'ownsDevice').mockResolvedValue(false);
});

afterEach(() => vi.restoreAllMocks());

function setup(trial = false) {
    const client = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
        },
    });
    render(
        <QueryClientProvider client={client}>
            <MemoryRouter>
                <Session.Provider
                    value={{
                        user: {
                            id: 'member',
                            name: '회원',
                            email: 'member@example.invalid',
                            role: 'USER',
                            trial,
                        },
                        loading: false,
                        error: null,
                        refresh: async () => {},
                    }}
                >
                    <DeliveryChannels />
                </Session.Provider>
            </MemoryRouter>
        </QueryClientProvider>,
    );
    return userEvent.setup();
}

it('never queries private delivery settings for a trial account', () => {
    setup(true);
    expect(api.settings).not.toHaveBeenCalled();
    expect(api.ownsDevice).not.toHaveBeenCalled();
});

it('retains the previous email setting after a failed save', async () => {
    vi.mocked(api.email).mockRejectedValue(new Error('저장 실패'));
    const user = setup();
    await user.click(
        await screen.findByRole('button', { name: '이메일 알림 켜기' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('저장 실패');
    expect(api.email).toHaveBeenCalledWith(true);
    expect(
        screen.getByRole('button', { name: '이메일 알림 켜기' }),
    ).toBeEnabled();
});

it('re-fetches channel settings after a successful save', async () => {
    const user = setup();
    await screen.findByRole('button', { name: '이메일 알림 켜기' });
    vi.mocked(api.settings).mockResolvedValue({
        emailEnabled: true,
        emailVerified: true,
        mailMode: 'smtp',
        publicKey: null,
    });
    await user.click(screen.getByRole('button', { name: '이메일 알림 켜기' }));
    await waitFor(() =>
        expect(
            screen.getByRole('button', { name: '이메일 알림 끄기' }),
        ).toBeEnabled(),
    );
});
