import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import { demoRequest, resetDemo } from '../features/housing/demo';
import { api } from '../features/housing/api';
vi.mock('../shared/api/transport', () => ({
    demoMode: true,
    call: (path: string, method = 'GET', body?: unknown) =>
        demoRequest(path, method, body),
    clearToken: vi.fn(),
    setToken: vi.fn(),
}));
function mount(path = '/') {
    render(
        <QueryClientProvider
            client={
                new QueryClient({
                    defaultOptions: {
                        queries: { retry: false },
                        mutations: { retry: false },
                    },
                })
            }
        >
            <MemoryRouter initialEntries={[path]}>
                <App />
            </MemoryRouter>
        </QueryClientProvider>,
    );
}
beforeEach(() => {
    HTMLDialogElement.prototype.showModal = function () {
        this.open = true;
    };
    HTMLDialogElement.prototype.close = function () {
        this.open = false;
    };
    resetDemo();
    vi.stubGlobal('scrollTo', vi.fn());
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
});
describe('사용자 준비 흐름', () => {
    it('비회원 공고 조회와 미검수 안내 시작 차단', async () => {
        mount('/notices/2');
        expect(
            await screen.findByRole('heading', {
                name: '[시연] 주거비 지원 예시',
            }),
        ).toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: '내 조건 확인하기' }),
        ).not.toBeInTheDocument();
        await userEvent.click(screen.getByRole('tab', { name: '조건' }));
        expect(
            screen.getByText(/검수되지 않은 개인별 안내/),
        ).toBeInTheDocument();
    });
    it('로그인 복귀부터 모름 응답 저장과 서류 실패 후 재시도까지', async () => {
        mount('/notices/1');
        await userEvent.click(
            await screen.findByRole('button', { name: '내 조건 확인하기' }),
        );
        await userEvent.click(
            await screen.findByRole('button', { name: '시연 사용자로 보기' }),
        );
        await userEvent.click(
            await screen.findByRole('button', { name: '내 조건 확인하기' }),
        );
        const unknown = await screen.findByRole('radio', {
            name: '잘 모르겠어요',
        });
        expect(unknown).not.toBeChecked();
        await userEvent.click(unknown);
        await userEvent.click(
            screen.getByRole('button', { name: '저장 후 다음' }),
        );
        await screen
            .findByText('공고 기준일의 거주 조건을 확인했나요?', {
                exact: true,
            })
            .catch(() => null);
        await waitFor(() =>
            expect(screen.getByRole('group')).toHaveAccessibleName(
                '본인에게 적용되는 무주택 기준을 확인했나요?',
            ),
        );
        await userEvent.click(
            screen.getByRole('radio', { name: '확인했어요' }),
        );
        await userEvent.click(
            screen.getByRole('button', { name: '저장 후 결과 보기' }),
        );
        expect(
            await screen.findByRole('heading', { name: '내 조건 확인 결과' }),
        ).toBeInTheDocument();
        expect(screen.getByText('추가 확인 필요')).toBeInTheDocument();
        await userEvent.click(
            screen.getByRole('link', { name: '내 준비 목록 보기' }),
        );
        await userEvent.click(
            await screen.findByText('거주 확인 서류 · 미확인', {
                exact: false,
            }),
        );
        const selects = screen.getAllByLabelText('준비 상태');
        await userEvent.selectOptions(selects[0], 'READY');
        const save = vi
            .spyOn(api, 'document')
            .mockRejectedValueOnce(new Error('시연 저장 실패'));
        await userEvent.click(
            screen.getAllByRole('button', {
                name: '이 서류 준비 상태 저장',
            })[0],
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            '시연 저장 실패',
        );
        expect(screen.getByText(/준비 완료 0개/)).toBeInTheDocument();
        save.mockRestore();
        await userEvent.click(
            screen.getAllByRole('button', {
                name: '이 서류 준비 상태 저장',
            })[0],
        );
        await waitFor(() =>
            expect(screen.getByText(/준비 완료 1개/)).toBeInTheDocument(),
        );
    }, 15000);
    it('검색의 AND 조건과 빈 결과 초기화', async () => {
        mount('/notices?region=서울&type=주거비+지원');
        expect(
            await screen.findByText('조건에 맞는 공고가 없어요'),
        ).toBeInTheDocument();
        await userEvent.click(
            screen.getByRole('button', { name: '검색 조건 초기화' }),
        );
        expect(
            await screen.findByRole('link', {
                name: '[시연] 청년 주거 준비 예시 ↗',
            }),
        ).toBeInTheDocument();
    });
    it('일반 회원에게 관리자 접근을 허용하지 않는다', async () => {
        await api.login('demo@example.invalid', 'demo');
        mount('/admin');
        expect(
            await screen.findByRole('heading', {
                name: '관리자 권한이 필요합니다',
            }),
        ).toBeInTheDocument();
    });
});
