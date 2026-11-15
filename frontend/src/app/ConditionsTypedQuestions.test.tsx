import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import { demoRequest, resetDemo, notices } from '../features/housing/demo';
import { api } from '../features/housing/api';
import { HistorySnapshot } from '../features/preparation/history/HistorySnapshot';
vi.mock('../shared/api/transport', () => ({
    demoMode: true,
    call: (path: string, method = 'GET', body?: unknown) =>
        demoRequest(path, method, body),
    clearToken: vi.fn(),
    setToken: vi.fn(),
}));

async function mount() {
    await api.login('demo@example.invalid', 'demo');
    const p = await api.start('1', 1);
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
            <MemoryRouter
                initialEntries={['/preparations/' + p.id + '/conditions/edit']}
            >
                <App />
            </MemoryRouter>
        </QueryClientProvider>,
    );
    return p;
}
beforeEach(() => {
    resetDemo();
    const original = notices[0].questions[0];
    notices[0].questions = [
        {
            ...original,
            id: 'house',
            title: '여러 항목 테스트',
            inputKind: 'MULTIPLE',
            constraints: { maxSelections: 2 },
            options: [
                { value: 'YES', label: '첫째 항목' },
                { value: 'NO', label: '둘째 항목' },
                { value: 'UNKNOWN', label: '잘 모르겠어요' },
            ],
        },
        {
            ...original,
            id: 'amount',
            title: '숫자 테스트',
            inputKind: 'NUMBER',
            constraints: { min: 0, max: 10, step: 0.5, unit: '개' },
        },
        {
            ...original,
            id: 'date',
            title: '날짜 테스트',
            inputKind: 'DATE',
            constraints: { minDate: '2024-01-01', maxDate: '2024-12-31' },
        },
    ];
    vi.stubGlobal('scrollTo', vi.fn());
    vi.stubGlobal(
        'matchMedia',
        vi.fn((media: string) => ({
            matches: false,
            media,
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
        })),
    );
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
});
afterEach(() => vi.restoreAllMocks());

describe('숫자·날짜·다중 선택 조건 확인', () => {
    it('다중 선택과 숫자·날짜는 다음으로 저장하고 결과와 이력에 실제 답변을 표시한다', async () => {
        const p = await mount();
        const save = vi.spyOn(api, 'answers');
        await userEvent.click(
            await screen.findByRole('checkbox', { name: '첫째 항목' }),
        );
        await userEvent.click(
            screen.getByRole('checkbox', { name: '둘째 항목' }),
        );
        expect(save).not.toHaveBeenCalled();
        expect(screen.getByRole('group')).toHaveAccessibleName(
            '여러 항목 테스트',
        );
        await userEvent.click(screen.getByRole('button', { name: '다음' }));
        const input = await screen.findByRole('spinbutton', {
            name: '숫자 입력',
        });
        fireEvent.change(input, { target: { value: '0' } });
        expect(save).toHaveBeenCalledTimes(1);
        expect(screen.getByRole('button', { name: '다음' })).toBeEnabled();
        await userEvent.click(screen.getByRole('button', { name: '다음' }));
        fireEvent.change(await screen.findByLabelText('날짜 입력'), {
            target: { value: '2024-02-29' },
        });
        expect(save).toHaveBeenCalledTimes(2);
        await userEvent.click(screen.getByRole('button', { name: '다음' }));
        await screen.findByRole('heading', { name: '내 조건 확인 결과' });
        const saved = await api.preparation(p.id);
        expect(saved.answers).toEqual({
            house: ['YES', 'NO'],
            amount: 0,
            date: '2024-02-29',
        });
        await userEvent.click(screen.getByText('답변 완료 3개 보기'));
        expect(screen.getByText('첫째 항목, 둘째 항목')).toBeVisible();
        expect(screen.getByText('0 개')).toBeVisible();
        expect(screen.getByText('2024-02-29')).toBeVisible();
        const history = render(<HistorySnapshot preparation={saved} />);
        expect(history.container).toHaveTextContent('첫째 항목, 둘째 항목');
        expect(history.container).toHaveTextContent('0 개');
        expect(history.container.querySelector('input')).toBeNull();
    });
    it('모름을 단독으로 저장하며 오류·이전 이동 후에도 수정 중인 입력을 잃지 않는다', async () => {
        await mount();
        await userEvent.click(
            await screen.findByRole('checkbox', { name: '첫째 항목' }),
        );
        await userEvent.click(
            screen.getByRole('checkbox', { name: '잘 모르겠어요' }),
        );
        expect(
            screen.getByRole('checkbox', { name: '첫째 항목' }),
        ).not.toBeChecked();
        await userEvent.click(screen.getByRole('button', { name: '다음' }));
        const input = await screen.findByRole('spinbutton');
        fireEvent.change(input, { target: { value: '11' } });
        expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
        await userEvent.click(screen.getByRole('button', { name: '이전' }));
        await userEvent.click(screen.getByRole('button', { name: '다음' }));
        expect(await screen.findByRole('spinbutton')).toHaveValue(11);
        expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
        await userEvent.clear(screen.getByRole('spinbutton'));
        await userEvent.type(screen.getByRole('spinbutton'), '1.5');
        vi.spyOn(api, 'answers').mockRejectedValueOnce(
            new Error('저장 재시도 테스트'),
        );
        await userEvent.click(screen.getByRole('button', { name: '다음' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            '저장 재시도 테스트',
        );
        expect(screen.getByRole('spinbutton')).toHaveValue(1.5);
        await userEvent.click(screen.getByRole('button', { name: '다음' }));
        await waitFor(() =>
            expect(screen.getByRole('group')).toHaveAccessibleName(
                '날짜 테스트',
            ),
        );
        fireEvent.change(screen.getByLabelText('날짜 입력'), {
            target: { value: '2025-01-01' },
        });
        expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
        await userEvent.click(
            screen.getByRole('checkbox', { name: '잘 모르겠어요' }),
        );
        expect(screen.getByRole('button', { name: '다음' })).toBeEnabled();
        expect(screen.getByLabelText('날짜 입력')).toBeDisabled();
    });
});
