import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Session } from './useSession';
import { AccountConnections } from './AccountConnections';
import {
    accountConnectionsApi as api,
    type AccountConnectionOverview,
} from './accountConnectionsApi';

const overview: AccountConnectionOverview = {
    passwordLogin: true,
    mailMode: 'smtp',
    providers: [
        {
            id: 'google',
            connected: false,
            available: true,
            canDisconnect: false,
        },
        {
            id: 'kakao',
            connected: false,
            available: false,
            canDisconnect: false,
        },
    ],
};
beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
    vi.spyOn(api, 'overview').mockResolvedValue(structuredClone(overview));
    vi.spyOn(api, 'requestProof').mockResolvedValue();
    vi.spyOn(api, 'start').mockResolvedValue({
        authorizationUrl: '/oauth2/authorization/google?connect=1',
    });
    vi.spyOn(api, 'disconnect').mockResolvedValue();
    vi.spyOn(api, 'openProvider').mockImplementation(() => {});
    vi.spyOn(api, 'returnToLogin').mockImplementation(() => {});
});
afterEach(() => vi.restoreAllMocks());
function setup({ trial = false, route = '/settings' } = {}) {
    const client = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
        },
    });
    render(
        <QueryClientProvider client={client}>
            <MemoryRouter initialEntries={[route]}>
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
                    <AccountConnections />
                </Session.Provider>
            </MemoryRouter>
        </QueryClientProvider>,
    );
    return userEvent.setup();
}

it('does not fetch account security controls for a trial account', () => {
    setup({ trial: true });
    expect(
        screen.queryByRole('heading', { name: '로그인 수단' }),
    ).not.toBeInTheDocument();
    expect(api.overview).not.toHaveBeenCalled();
});

it('shows provider configuration and asks for current password before leaving the app', async () => {
    const user = setup();
    await user.click(
        await screen.findByRole('button', { name: 'Google 연결' }),
    );
    expect(screen.getByRole('button', { name: '카카오 연결' })).toBeDisabled();
    const form = screen.getByRole('form', { name: 'Google 연결 확인' });
    expect(
        within(form).getByRole('button', { name: '확인 후 연결' }),
    ).toBeDisabled();
    expect(api.start).not.toHaveBeenCalled();
    await user.type(
        within(form).getByLabelText('현재 비밀번호', {
            selector: 'input[type="password"]',
        }),
        'correct-password',
    );
    await user.click(
        within(form).getByRole('button', { name: '확인 후 연결' }),
    );
    await waitFor(() =>
        expect(api.start).toHaveBeenCalledWith('google', {
            password: 'correct-password',
        }),
    );
    expect(api.openProvider).toHaveBeenCalledWith(
        '/oauth2/authorization/google?connect=1',
    );
});

it('uses the one-time email code for a social-only account and never asks for a nonexistent password', async () => {
    vi.mocked(api.overview).mockResolvedValue({
        ...overview,
        passwordLogin: false,
    });
    const user = setup();
    await user.click(
        await screen.findByRole('button', { name: 'Google 연결' }),
    );
    expect(screen.queryByLabelText('현재 비밀번호')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '확인 코드 받기' }));
    expect(await screen.findByRole('status')).toHaveTextContent(
        '등록한 이메일',
    );
    await user.type(
        screen.getByLabelText('이메일 확인 코드'),
        'single-use-proof',
    );
    await user.click(screen.getByRole('button', { name: '확인 후 연결' }));
    await waitFor(() =>
        expect(api.start).toHaveBeenCalledWith('google', {
            code: 'single-use-proof',
        }),
    );
});

it('keeps failed authentication editable and does not redirect', async () => {
    vi.mocked(api.start).mockRejectedValue(
        new Error('현재 비밀번호를 확인해 주세요.'),
    );
    const user = setup();
    await user.click(
        await screen.findByRole('button', { name: 'Google 연결' }),
    );
    const input = screen.getByLabelText('현재 비밀번호', {
        selector: 'input[type="password"]',
    });
    await user.type(input, 'wrong-password');
    await user.click(screen.getByRole('button', { name: '확인 후 연결' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('현재 비밀번호');
    expect(input).toHaveValue('wrong-password');
    expect(api.openProvider).not.toHaveBeenCalled();
});

it('disables removing the last usable sign-in method', async () => {
    vi.mocked(api.overview).mockResolvedValue({
        ...overview,
        passwordLogin: false,
        providers: [
            {
                id: 'google',
                connected: true,
                available: true,
                canDisconnect: false,
            },
        ],
    });
    setup();
    expect(
        await screen.findByRole('button', { name: 'Google 연결 해제' }),
    ).toBeDisabled();
    expect(
        screen.getByText(/마지막 로그인 수단은 해제할 수 없어요/),
    ).toBeInTheDocument();
});

it('requires explicit confirmation before disconnecting and returns to login only after success', async () => {
    vi.mocked(api.overview).mockResolvedValue({
        ...overview,
        providers: [
            {
                id: 'google',
                connected: true,
                available: true,
                canDisconnect: true,
            },
        ],
    });
    const user = setup();
    await user.click(
        await screen.findByRole('button', { name: 'Google 연결 해제' }),
    );
    expect(api.disconnect).not.toHaveBeenCalled();
    expect(screen.getByText(/모든 기기에서 로그아웃/)).toBeInTheDocument();
    await user.type(
        screen.getByLabelText('현재 비밀번호', {
            selector: 'input[type="password"]',
        }),
        'current-password',
    );
    await user.click(
        within(
            screen.getByRole('form', { name: 'Google 연결 해제 확인' }),
        ).getByRole('button', { name: '연결 해제' }),
    );
    await waitFor(() =>
        expect(api.disconnect).toHaveBeenCalledWith('google', {
            password: 'current-password',
        }),
    );
    expect(api.returnToLogin).toHaveBeenCalledOnce();
});

it('describes a cancelled or rejected provider callback without claiming success', async () => {
    setup({ route: '/settings?connection=failed' });
    expect(await screen.findByRole('alert')).toHaveTextContent(
        '연결하지 못했습니다',
    );
    expect(
        screen.queryByText('로그인 수단을 연결했습니다.'),
    ).not.toBeInTheDocument();
});
