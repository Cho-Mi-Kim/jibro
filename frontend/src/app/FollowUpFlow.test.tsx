import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { App } from './App';
import { api } from '../features/housing/api';
import { notices, resetDemo, demoRequest } from '../features/housing/demo';
import { memberApi } from '../features/member/memberApi';
import { emptyFollowUp } from '../features/housing/followUp';
import { HistorySnapshot } from '../features/preparation/history/HistorySnapshot';
import { applicationRecordSchedule } from '../features/housing/schedules';

vi.mock('../features/housing/demoDelay', () => ({
    waitForDemoResponse: async () => {},
}));
vi.mock('../shared/api/transport', () => ({
    demoMode: true,
    call: (path: string, method = 'GET', body?: unknown) =>
        demoRequest(path, method, body),
    clearToken: vi.fn(),
    setToken: vi.fn(),
}));

const dialogMethods = Object.fromEntries(
    ['showModal', 'close'].map((key) => [
        key,
        Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, key),
    ]),
);
afterEach(() => {
    for (const [key, descriptor] of Object.entries(dialogMethods)) {
        if (descriptor)
            Object.defineProperty(HTMLDialogElement.prototype, key, descriptor);
        else Reflect.deleteProperty(HTMLDialogElement.prototype, key);
    }
});
beforeEach(() => {
    resetDemo();
    localStorage.clear();
    vi.stubGlobal('scrollTo', vi.fn());
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
    HTMLElement.prototype.scrollTo = vi.fn();
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
        configurable: true,
        writable: true,
        value: function (this: HTMLDialogElement) {
            this.open = true;
        },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
        configurable: true,
        writable: true,
        value: function (this: HTMLDialogElement) {
            this.open = false;
            this.dispatchEvent(new Event('close'));
        },
    });
    notices[0].schedules = [
        {
            id: 'apply',
            title: '신청 접수 마감',
            date: '2026-12-01',
            evidence: notices[0].evidence,
        },
        {
            id: 'docs',
            title: '서류 제출 마감',
            date: '2026-12-05',
            evidence: notices[0].evidence,
        },
        {
            id: 'result',
            title: '입주자 선정 결과 발표',
            date: '2026-12-10',
            evidence: notices[0].evidence,
        },
        {
            id: 'contract',
            title: '계약',
            date: '2026-12-15',
            evidence: notices[0].evidence,
        },
        {
            id: 'move',
            title: '입주 지정 기간',
            date: '2027-01-01',
            evidence: notices[0].evidence,
        },
    ];
});
function mount(path: string) {
    return render(
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

async function startApplied() {
    const p = await api.start('1', 1);
    return api.schedule(
        p.id,
        applicationRecordSchedule(p.schedules)!.id,
        p.revision,
        true,
    );
}

describe('신청 후 실제 화면 흐름', () => {
    it.each([false, true])(
        '개요와 접수 마감이 같은 신청 기록을 양방향 공유하고 단순 접수 일정은 기록하지 않는다 (별도 신청 기록 %s)',
        async (legacy) => {
            const evidence = notices[0].evidence;
            notices[0].schedules!.push(
                {
                    id: 'start',
                    title: '신청 접수 시작',
                    date: '2026-11-01',
                    evidence,
                },
                {
                    id: 'session',
                    title: '접수 회차 · 현장 접수',
                    date: '2026-11-20',
                    evidence,
                },
            );
            if (legacy)
                notices[0].schedules!.push({
                    id: 'legacy-apply',
                    title: '공식 신청 여부 확인',
                    date: '2026-12-01',
                    evidence,
                });
            await api.login('demo@example.invalid', 'demo');
            const p = await api.start('1', 1);
            mount(`/preparations/${p.id}`);
            await userEvent.click(
                await screen.findByRole('button', {
                    name: '신청 여부 기록',
                }),
            );
            await userEvent.click(
                screen.getByRole('button', { name: '완료했어요' }),
            );
            await screen.findByRole('button', {
                name: '신청 완료',
            });
            await userEvent.click(
                within(
                    screen.getByRole('navigation', { name: '개인 준비' }),
                ).getByRole('link', { name: '일정' }),
            );
            const end = (
                await screen
                    // A cold lazy route can compile alongside the other CI workers.
                    // This checks loaded content, rather than a one-second import budget.
                    .findByRole(
                        'heading',
                        { name: '전체 일정' },
                        { timeout: 5000 },
                    )
                    .then((heading) =>
                        within(
                            heading.closest('section') ??
                                heading.parentElement!.parentElement!,
                        ).getByText('신청 접수 마감', {
                            selector: 'li strong',
                        }),
                    )
            ).closest('li')!;
            expect(within(end).queryByRole('button')).not.toBeInTheDocument();
            for (const action of screen.queryAllByRole('button', {
                name: /^(신청 완료|취소|신청 여부 기록)$/,
            }))
                expect(action).not.toBeVisible();
            expect(
                screen.queryByRole('link', {
                    name: '신청 후 결과·진행 상황 기록',
                }),
            ).not.toBeInTheDocument();
            await userEvent.click(
                screen.getByLabelText('일정 근거와 안내 펼치기'),
            );
            const application = applicationRecordSchedule(p.schedules)!;
            const applicationSummary = screen.getByText(application.title, {
                selector: 'summary h3',
            });
            const applicationCard = applicationSummary.closest('details')!;
            await userEvent.click(applicationSummary);
            expect(
                within(applicationCard).queryByRole('button'),
            ).not.toBeInTheDocument();
            expect(
                within(applicationCard).queryByText(/완료.*기록/),
            ).not.toBeInTheDocument();
            for (const title of ['신청 접수 시작', '접수 회차 · 현장 접수']) {
                const cards = screen
                    .getAllByText(title, { selector: 'strong,h3' })
                    .map((el) => el.closest('details'))
                    .filter(Boolean);
                for (const card of cards) {
                    expect(
                        within(card!).queryByText(/완료.*기록/),
                    ).not.toBeInTheDocument();
                    expect(
                        within(card!).queryByRole('button'),
                    ).not.toBeInTheDocument();
                }
            }
            let current = await api.preparation(p.id);
            for (const id of ['start', 'session'])
                await expect(
                    api.schedule(p.id, id, current.revision, true),
                ).rejects.toMatchObject({ status: 400 });
            expect((await api.preparation(p.id)).revision).toBe(
                current.revision,
            );
            await userEvent.click(
                within(
                    screen.getByRole('navigation', { name: '개인 준비' }),
                ).getByRole('link', { name: '개요' }),
            );
            await userEvent.click(
                await screen.findByRole('button', { name: '신청 완료' }),
            );
            await userEvent.click(
                screen.getByRole('button', { name: '아직이에요' }),
            );
            await screen.findByRole('button', { name: '신청 여부 기록' });
            current = await api.preparation(p.id);
            expect(applicationRecordSchedule(current.schedules)?.recorded).toBe(
                false,
            );
        },
    );

    it('신청 전 4개 탭과 직접 주소 보호, 신청 저장 후 5개 탭·후속 진행, 결과 저장 후 전체 완료, 신청 취소 후 숨김을 함께 반영한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount(`/preparations/${p.id}/follow-up`);
        await screen.findByRole('button', { name: '신청 여부 기록' });
        const nav = () => screen.getByRole('navigation', { name: '개인 준비' });
        const journey = () => screen.getByRole('list', { name: '준비 단계' });
        expect(within(nav()).getAllByRole('link')).toHaveLength(4);
        expect(
            screen.queryByRole('radio', { name: '선정' }),
        ).not.toBeInTheDocument();
        expect(
            screen.queryByRole('link', { name: '신청 후 진행 상황' }),
        ).not.toBeInTheDocument();
        await userEvent.click(
            screen.getByRole('button', { name: '신청 여부 기록' }),
        );
        await userEvent.click(
            screen.getByRole('button', { name: '완료했어요' }),
        );
        await waitFor(() =>
            expect(within(nav()).getAllByRole('link')).toHaveLength(5),
        );
        expect(
            journey().querySelector('[aria-current="step"]'),
        ).toHaveTextContent('후속 절차진행 중');
        expect(journey().querySelectorAll('[data-done="true"]')).toHaveLength(
            4,
        );
        await userEvent.click(screen.getAllByRole('link', { name: '홈' })[0]);
        await waitFor(() =>
            expect(
                screen.queryByRole('navigation', { name: '개인 준비' }),
            ).not.toBeInTheDocument(),
        );
        expect(
            journey().querySelector('[aria-current="step"]'),
        ).toHaveTextContent('후속 절차진행 중');
        await userEvent.click(
            screen.getByRole('link', { name: '신청 후 진행 상황' }),
        );
        await userEvent.click(
            await screen.findByRole('button', { name: '진행 상황 저장' }),
        );
        await screen.findByText('신청 후 진행 상황을 저장했어요.');
        await userEvent.click(screen.getByRole('link', { name: '개요' }));
        await waitFor(() =>
            expect(
                journey().querySelector('[aria-current="step"]'),
            ).toHaveTextContent('후속 절차진행 중'),
        );
        await userEvent.click(screen.getByRole('link', { name: '신청 후' }));
        await userEvent.click(
            await screen.findByRole('radio', { name: '선정' }),
        );
        await userEvent.click(
            screen.getByRole('button', { name: '진행 상황 저장' }),
        );
        await screen.findByText('신청 후 진행 상황을 저장했어요.');
        await userEvent.click(screen.getByRole('link', { name: '개요' }));
        await waitFor(() =>
            expect(journey()).toHaveAttribute('data-completed', 'true'),
        );
        expect(journey().querySelectorAll('[data-done="true"]')).toHaveLength(
            5,
        );
        expect(journey().querySelector('[aria-current]')).toBeNull();
        expect(journey()).not.toHaveTextContent('전체 완료');
        await userEvent.click(
            screen.getByRole('button', { name: '신청 완료' }),
        );
        await userEvent.click(
            screen.getByRole('button', { name: '아직이에요' }),
        );
        await waitFor(() =>
            expect(within(nav()).getAllByRole('link')).toHaveLength(4),
        );
        expect(journey()).toHaveAttribute('data-completed', 'false');
        expect(
            screen.queryByRole('link', { name: '신청 후 진행 상황' }),
        ).not.toBeInTheDocument();
        expect((await api.preparation(p.id)).followUp?.result).toBe('SELECTED');
    });
    it('동시 수정 시 덮어쓰지 않고 최신 기록을 확인한 뒤 다시 저장한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await startApplied();
        mount(`/preparations/${p.id}/follow-up`);
        await userEvent.click(
            await screen.findByRole('radio', { name: '예비 입주' }),
        );
        await userEvent.type(screen.getByRole('spinbutton'), '5');
        await api.followUp(p.id, p.revision, {
            ...emptyFollowUp,
            result: 'SELECTED',
        });
        await userEvent.click(
            screen.getByRole('button', { name: '진행 상황 저장' }),
        );
        await screen.findByRole('alert');
        expect((await api.preparation(p.id)).followUp?.result).toBe('SELECTED');
        expect(screen.getByRole('spinbutton')).toHaveValue(5);
        await userEvent.click(
            screen.getByRole('button', { name: '최신 기록 다시 불러오기' }),
        );
        await waitFor(() =>
            expect(screen.getByRole('radio', { name: '선정' })).toBeChecked(),
        );
        await userEvent.click(
            screen.getByRole('checkbox', { name: '계약 완료로 기록' }),
        );
        await userEvent.click(
            screen.getByRole('button', { name: '진행 상황 저장' }),
        );
        await screen.findByText('신청 후 진행 상황을 저장했어요.');
        expect((await api.preparation(p.id)).followUp?.contractCompleted).toBe(
            true,
        );
    });
    it('실패 시 입력 유지, 저장 후 홈·내 준비·보관함에 같은 결과가 표시되고 복원된다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await startApplied();
        mount(`/preparations/${p.id}/follow-up`);
        await userEvent.click(
            await screen.findByRole('radio', { name: '예비 입주' }),
        );
        await userEvent.type(
            screen.getByRole('spinbutton', { name: /예비 순번/ }),
            '12',
        );
        vi.spyOn(api, 'followUp').mockRejectedValueOnce(new Error('저장 실패'));
        await userEvent.click(
            screen.getByRole('button', { name: '진행 상황 저장' }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent('저장 실패');
        expect(screen.getByRole('spinbutton')).toHaveValue(12);
        expect((await api.preparation(p.id)).followUp).toBeFalsy();
        await userEvent.click(
            screen.getByRole('button', { name: '진행 상황 저장' }),
        );
        await screen.findByText('신청 후 진행 상황을 저장했어요.');
        expect((await api.preparation(p.id)).progress).toBe(p.progress);
        await userEvent.click(screen.getAllByRole('link', { name: '홈' })[0]);
        expect(
            await screen.findByRole('heading', { name: '예비 대기 · 12번' }),
        ).toBeVisible();
        await userEvent.click(
            await screen.findByRole('link', { name: '신청 후 진행 상황' }),
        );
        expect(
            await screen.findByRole('radio', { name: '예비 입주' }),
        ).toBeChecked();
        await userEvent.click(screen.getByRole('radio', { name: '선정' }));
        await userEvent.click(
            screen.getByRole('checkbox', { name: '서류 제출 완료로 기록' }),
        );
        await userEvent.click(
            screen.getByRole('checkbox', { name: '계약 완료로 기록' }),
        );
        await userEvent.click(
            screen.getByRole('checkbox', { name: '입주 완료로 기록' }),
        );
        await userEvent.click(
            screen.getByRole('button', { name: '진행 상황 저장' }),
        );
        await screen.findByText('신청 후 진행 상황을 저장했어요.');
        const completed = await api.preparation(p.id);
        expect(completed.followUp).toEqual({
            ...emptyFollowUp,
            result: 'SELECTED',
            documentsSubmitted: true,
            contractCompleted: true,
            movedIn: true,
        });
        await userEvent.click(screen.getByRole('link', { name: '개요' }));
        expect(
            await screen.findByRole('heading', { name: '입주 완료' }),
        ).toBeVisible();
        await userEvent.click(
            await screen.findByRole('link', { name: '신청 후 진행 상황' }),
        );
        await screen.findByRole('checkbox', { name: '입주 완료로 기록' });
        await userEvent.click(
            screen.getByRole('checkbox', { name: '입주 완료로 기록' }),
        );
        await userEvent.click(
            screen.getByRole('button', { name: '변경 취소' }),
        );
        expect(
            screen.getByRole('checkbox', { name: '입주 완료로 기록' }),
        ).toBeChecked();
        await userEvent.click(
            screen.getByRole('link', { name: '준비 기록 보관하기' }),
        );
        await userEvent.click(
            await screen.findByRole('button', { name: '이 준비 건 보관' }),
        );
        expect(
            await screen.findByRole('region', { name: '당시 신청 후 기록' }),
        ).toHaveTextContent('입주 완료');
        expect((await memberApi.archive())[0].followUp).toEqual(
            completed.followUp,
        );
        await userEvent.click(
            screen.getByRole('button', { name: '내 준비로 복원' }),
        );
        await waitFor(() =>
            expect(
                screen.queryByRole('region', { name: '당시 신청 후 기록' }),
            ).not.toBeInTheDocument(),
        );
        expect((await api.preparation(p.id)).followUp).toEqual(
            completed.followUp,
        );
    });

    it('공고 정정 중에도 개인 결과를 기록하고 이전 기록은 당시 값으로만 표시한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await startApplied();
        const original = await api.followUp(p.id, p.revision, {
            ...emptyFollowUp,
            result: 'WAITLIST',
            waitlistNumber: 4,
        });
        notices[0].version++;
        const changed = await api.followUp(p.id, original.revision, {
            ...emptyFollowUp,
            result: 'NOT_SELECTED',
        });
        expect(changed.guideState).toBe('CHANGE_REVIEW');
        expect(original.followUp?.result).toBe('WAITLIST');
        render(
            <MemoryRouter>
                <HistorySnapshot preparation={original} />
            </MemoryRouter>,
        );
        const record = screen.getByRole('region', {
            name: '당시 신청 후 기록',
        });
        expect(record).toHaveTextContent('예비 대기 · 4번');
        expect(within(record).queryByRole('button')).not.toBeInTheDocument();
        expect(record).not.toHaveTextContent('미선정');
    });
});
