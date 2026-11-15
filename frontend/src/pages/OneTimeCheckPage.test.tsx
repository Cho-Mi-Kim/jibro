import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
    Link,
    MemoryRouter,
    Route,
    Routes,
    useLocation,
    useNavigate,
} from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { OneTimeCheckPage } from './OneTimeCheckPage';
import { demoRequest, resetDemo } from '../features/housing/demo';
import { api } from '../features/housing/api';

vi.mock('../shared/api/transport', () => ({
    demoMode: true,
    call: (path: string, method = 'GET', body?: unknown) =>
        demoRequest(path, method, body),
}));
function Location() {
    const location = useLocation();
    const navigate = useNavigate();
    return (
        <>
            <output aria-label="현재 경로">
                {location.pathname + location.search}
            </output>
            <Link to="/guide">가이드로 이동</Link>
            <button onClick={() => navigate(-1)}>브라우저 뒤로가기</button>
        </>
    );
}
function mount(path = '/notices/1/check?version=1') {
    const cache = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    render(
        <QueryClientProvider client={cache}>
            <MemoryRouter initialEntries={[path]}>
                <Routes>
                    <Route
                        path="/notices/:noticeId/check"
                        element={<OneTimeCheckPage mode="choice" />}
                    />
                    <Route
                        path="/notices/:noticeId/check/once"
                        element={<OneTimeCheckPage mode="once" />}
                    />
                    <Route
                        path="/notices/:noticeId/start"
                        element={<p>저장 경로</p>}
                    />
                    <Route path="/guide" element={<p>가이드 화면</p>} />
                </Routes>
                <Location />
            </MemoryRouter>
        </QueryClientProvider>,
    );
    return cache;
}
beforeEach(() => {
    resetDemo();
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
});
describe('조건 확인 방법 선택', () => {
    it('라우터를 떠난 뒤 브라우저 뒤로가기로 같은 주소에 돌아와도 답변·결과는 되살아나지 않는다', async () => {
        mount('/notices/1/check/once?version=1');
        await userEvent.click(
            await screen.findByRole('radio', { name: '확인했어요' }),
        );
        await userEvent.click(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        await userEvent.click(
            screen.getByRole('link', { name: '가이드로 이동' }),
        );
        expect(screen.getByText('가이드 화면')).toBeVisible();
        await userEvent.click(
            screen.getByRole('button', { name: '브라우저 뒤로가기' }),
        );
        expect(
            await screen.findByRole('radio', { name: '확인했어요' }),
        ).not.toBeChecked();
        expect(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        ).not.toBeChecked();
        expect(screen.getByLabelText('현재 경로')).toHaveTextContent(
            '/notices/1/check/once?version=1',
        );
        expect(
            screen.queryByRole('heading', { name: '이번 조건 확인 결과' }),
        ).not.toBeInTheDocument();
    });
    it('로그인 없이 확인하고도 개인 준비·로그인·캐시 저장 없이 결과를 보여 준다', async () => {
        const start = vi.spyOn(api, 'start');
        try {
            const cache = mount();
            await userEvent.click(
                await screen.findByRole('button', {
                    name: '저장하지 않고 이번만 확인',
                }),
            );
            await userEvent.click(
                await screen.findByRole('radio', { name: '확인했어요' }),
            );
            await userEvent.click(
                screen.getByRole('radio', { name: '해당하지 않아요' }),
            );
            expect(
                await screen.findByRole('heading', {
                    name: '이번 조건 확인 결과',
                }),
            ).toBeVisible();
            expect(start).not.toHaveBeenCalled();
            expect(cache.getMutationCache().getAll()).toEqual([]);
            expect(
                cache
                    .getQueryCache()
                    .getAll()
                    .map((query) => query.queryKey),
            ).toEqual([['notice', '1']]);
            await expect(api.preparations()).rejects.toMatchObject({
                status: 401,
            });
        } finally {
            start.mockRestore();
        }
    });
    it('저장을 선택해야 기존 시작 경로로 이동하며 홈 복귀 목적을 유지한다', async () => {
        mount('/notices/1/check?version=1&view=home');
        await userEvent.click(
            await screen.findByRole('button', { name: '저장하고 이어하기' }),
        );
        const destination = new URL(
            screen.getByLabelText('현재 경로').textContent ?? '',
            'http://test.local',
        );
        expect(destination.pathname).toBe('/notices/1/start');
        expect(destination.searchParams.get('version')).toBe('1');
        expect(destination.searchParams.get('view')).toBe('home');
        expect(destination.searchParams.get('ticket')).toBeTruthy();
    });
    it.each(['/notices/2/check', '/notices/1/check/once?version=2'])(
        '미검수·오래된 안내는 직접 주소로도 시작하지 않는다 (%s)',
        async (path) => {
            mount(path);
            expect(await screen.findByRole('alert')).toHaveTextContent(
                '조건 확인을 시작할 수 없습니다',
            );
            expect(screen.queryByRole('radio')).not.toBeInTheDocument();
            expect(
                screen.queryByRole('button', {
                    name: '저장하지 않고 이번만 확인',
                }),
            ).not.toBeInTheDocument();
        },
    );
});
