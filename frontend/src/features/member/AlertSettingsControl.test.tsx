import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { Session } from '../auth/useSession';
import type { User } from '../housing/types';
import { memberApi } from './memberApi';
import { AlertSettingsControl } from './AlertSettingsControl';

const originalMethods = Object.getOwnPropertyDescriptors(
    HTMLDialogElement.prototype,
);
beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
        configurable: true,
        value: function (this: HTMLDialogElement) {
            this.open = true;
        },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
        configurable: true,
        value: function (this: HTMLDialogElement) {
            this.open = false;
            this.dispatchEvent(new Event('close'));
        },
    });
});
afterEach(() => {
    vi.restoreAllMocks();
    for (const method of ['showModal', 'close']) {
        const original = originalMethods[method];
        if (original)
            Object.defineProperty(
                HTMLDialogElement.prototype,
                method,
                original,
            );
        else Reflect.deleteProperty(HTMLDialogElement.prototype, method);
    }
});

function Location() {
    const location = useLocation();
    return (
        <output aria-label="현재 주소">
            {location.pathname + location.search}
        </output>
    );
}
function mount(user: User | null = null, loading = false) {
    return render(
        <MemoryRouter initialEntries={['/my?view=account']}>
            <QueryClientProvider
                client={
                    new QueryClient({
                        defaultOptions: { queries: { retry: false } },
                    })
                }
            >
                <Session.Provider
                    value={{
                        user,
                        loading,
                        error: null,
                        refresh: async () => {},
                    }}
                >
                    <AlertSettingsControl />
                    <Location />
                </Session.Provider>
            </QueryClientProvider>
        </MemoryRouter>,
    );
}

it('비회원에게 안내와 로그인 복귀 경로를 제공하고 알림 API는 호출하지 않는다', async () => {
    const inbox = vi.spyOn(memberApi, 'inbox');
    mount();
    await userEvent.click(screen.getByRole('button', { name: '알림 설정' }));
    expect(screen.getByText('로그인이 필요합니다.')).toBeVisible();
    const link = screen.getByRole('link', { name: '로그인' });
    expect(link).toHaveAttribute(
        'href',
        '/login?returnTo=%2Fmy%3Fview%3Daccount',
    );
    expect(inbox).not.toHaveBeenCalled();
    await userEvent.click(link);
    expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
        '/login?returnTo=%2Fmy%3Fview%3Daccount',
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});

it('세션 확인 중에는 로그인 안내나 알림 조회를 먼저 실행하지 않는다', async () => {
    const inbox = vi.spyOn(memberApi, 'inbox');
    mount(null, true);
    await userEvent.click(screen.getByRole('button', { name: '알림 설정' }));
    expect(screen.queryByText('로그인이 필요합니다.')).not.toBeInTheDocument();
    expect(inbox).not.toHaveBeenCalled();
});

it('로그인한 사용자는 기존 알림 설정을 연다', async () => {
    vi.spyOn(memberApi, 'inbox').mockResolvedValue({
        settings: { enabled: true, leadDays: 3, includeSaved: true },
        items: [],
    });
    mount({
        id: 'trial-test',
        name: '방문자',
        email: '',
        role: 'USER',
        trial: true,
    });
    await userEvent.click(screen.getByRole('button', { name: '알림 설정' }));
    expect(
        await screen.findByRole('button', { name: '알림 설정 저장' }),
    ).toBeVisible();
    expect(screen.queryByText('로그인이 필요합니다.')).not.toBeInTheDocument();
});
