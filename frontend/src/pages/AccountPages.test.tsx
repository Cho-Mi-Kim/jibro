import { StrictMode } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Session } from '../features/auth/useSession';
import { socialLogin } from '../features/auth/socialLogin';
import { api } from '../features/housing/api';
import { CallbackPage, LoginPage } from './AccountPages';

beforeEach(() => {
    sessionStorage.clear();
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
    vi.spyOn(api, 'providers').mockResolvedValue(['google', 'kakao']);
    vi.spyOn(api, 'meta').mockResolvedValue({ sampleData: false });
    vi.spyOn(socialLogin, 'start').mockImplementation(() => {});
});
afterEach(() => {
    sessionStorage.clear();
    vi.restoreAllMocks();
});

function Destination() {
    const location = useLocation();
    return <p>{location.pathname + location.search + location.hash}</p>;
}

function setup({ route = '/login', signedIn = false, loading = false } = {}) {
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    render(
        <StrictMode>
            <QueryClientProvider client={client}>
                <MemoryRouter initialEntries={[route]}>
                    <Session.Provider
                        value={{
                            user: signedIn
                                ? {
                                      id: 'member',
                                      name: '회원',
                                      email: 'member@example.invalid',
                                      role: 'USER',
                                  }
                                : null,
                            loading,
                            error: null,
                            refresh: async () => {},
                        }}
                    >
                        <Routes>
                            <Route path="/login" element={<LoginPage />} />
                            <Route
                                path="/auth/callback"
                                element={<CallbackPage />}
                            />
                            <Route path="*" element={<Destination />} />
                        </Routes>
                    </Session.Provider>
                </MemoryRouter>
            </QueryClientProvider>
            ,
        </StrictMode>,
    );
    return userEvent.setup();
}

it('keeps unconfigured providers disabled', async () => {
    vi.mocked(api.providers).mockResolvedValue([]);
    const user = setup();
    await waitFor(() => expect(api.providers).toHaveBeenCalled());
    const google = screen.getByRole('button', { name: 'Google로 계속하기' });
    const kakao = screen.getByRole('button', { name: '카카오로 계속하기' });
    expect(google).toBeDisabled();
    expect(kakao).toBeDisabled();
    await user.click(google);
    await user.click(kakao);
    expect(socialLogin.start).not.toHaveBeenCalled();
});

it.each([
    ['google', 'Google로 계속하기'],
    ['kakao', '카카오로 계속하기'],
] as const)(
    'starts the configured %s provider with the original destination',
    async (provider, label) => {
        vi.mocked(api.providers).mockResolvedValue([provider]);
        const user = setup({
            route: '/login?returnTo=%2Fnotices%2F17%3Fview%3Dconditions%23questions',
        });
        const button = screen.getByRole('button', { name: label });
        await waitFor(() => expect(button).toBeEnabled());
        await user.click(button);
        expect(socialLogin.start).toHaveBeenCalledWith(
            provider,
            '/notices/17?view=conditions#questions',
        );
        expect(
            screen.getByRole('button', {
                name:
                    provider === 'google'
                        ? '카카오로 계속하기'
                        : 'Google로 계속하기',
            }),
        ).toBeDisabled();
    },
);

it.each(['provider', 'account'])(
    'preserves the saved destination after an OAuth %s failure',
    async (error) => {
        sessionStorage.setItem('jibro.returnTo', '/notices/17?view=conditions');
        const user = setup({ route: '/login?oauthError=' + error });
        const button = screen.getByRole('button', {
            name: 'Google로 계속하기',
        });
        await waitFor(() => expect(button).toBeEnabled());
        await user.click(button);
        expect(socialLogin.start).toHaveBeenCalledWith(
            'google',
            '/notices/17?view=conditions',
        );
        expect(
            screen.getByRole('link', { name: '이메일로 회원가입' }),
        ).toHaveAttribute(
            'href',
            '/signup?returnTo=%2Fnotices%2F17%3Fview%3Dconditions',
        );
    },
);

it('returns to the saved path and clears it after callback session restoration', async () => {
    sessionStorage.setItem(
        'jibro.returnTo',
        '/notices/17?view=conditions#questions',
    );
    setup({ route: '/auth/callback', signedIn: true });
    expect(
        await screen.findByText('/notices/17?view=conditions#questions'),
    ).toBeInTheDocument();
    expect(sessionStorage.getItem('jibro.returnTo')).toBeNull();
});

it('retains the original path when retrying a callback without a restored session', async () => {
    sessionStorage.setItem('jibro.returnTo', '/notices/17?view=conditions');
    const user = setup({ route: '/auth/callback' });
    const retry = screen.getByRole('link', { name: '로그인으로 돌아가기' });
    expect(retry).toHaveAttribute(
        'href',
        '/login?returnTo=%2Fnotices%2F17%3Fview%3Dconditions',
    );
    await user.click(retry);
    const button = screen.getByRole('button', { name: '카카오로 계속하기' });
    await waitFor(() => expect(button).toBeEnabled());
    await user.click(button);
    expect(socialLogin.start).toHaveBeenCalledWith(
        'kakao',
        '/notices/17?view=conditions',
    );
});

it('waits for callback session restoration before offering a retry', () => {
    sessionStorage.setItem('jibro.returnTo', '/notices/17');
    setup({ route: '/auth/callback', loading: true });
    expect(screen.getByRole('status')).toBeInTheDocument();
    expect(
        screen.queryByRole('link', { name: '로그인으로 돌아가기' }),
    ).not.toBeInTheDocument();
    expect(sessionStorage.getItem('jibro.returnTo')).toBe('/notices/17');
});
