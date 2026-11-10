import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Session } from '../app/useSession';
import { api } from '../features/housing/api';
import type { Preparation } from '../features/housing/types';
import { memberApi } from '../features/member/memberApi';
import { ArchivePage } from './ArchivePage';

const records: Preparation[] = ['A', 'B'].map((id) => ({
    id,
    noticeId: id,
    title: `${id} 공고`,
    version: 1,
    revision: id === 'A' ? 2 : 7,
    guideState: 'AVAILABLE',
    questions: [],
    answers: {},
    documents: [],
    schedules: [],
    progress: 0,
    houseStage: 1,
    warnings: [],
    nextAction: { label: '', reason: '', path: '' },
}));
const originalScroll = Object.getOwnPropertyDescriptor(
    HTMLElement.prototype,
    'scrollIntoView',
);
beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
        configurable: true,
        value: vi.fn(),
    });
    vi.spyOn(api, 'preparations').mockResolvedValue(records);
    vi.spyOn(memberApi, 'archive').mockResolvedValue([
        {
            id: 'old',
            title: '보관 공고',
            version: 1,
            progress: 40,
            note: '지난 경험',
            archivedAt: '2026-01-01',
        },
    ]);
    vi.spyOn(memberApi, 'finish').mockResolvedValue();
    vi.spyOn(memberApi, 'restore').mockResolvedValue();
    vi.spyOn(memberApi, 'reuse').mockResolvedValue();
});
afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    if (originalScroll)
        Object.defineProperty(
            HTMLElement.prototype,
            'scrollIntoView',
            originalScroll,
        );
    else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
});
function mount() {
    return render(
        <QueryClientProvider
            client={
                new QueryClient({
                    defaultOptions: {
                        queries: { retry: false, gcTime: 0 },
                        mutations: { retry: false },
                    },
                })
            }
        >
            <Session.Provider
                value={{
                    user: {
                        id: 'archive-review',
                        name: '방문자',
                        email: '',
                        role: 'USER',
                    },
                    loading: false,
                    error: null,
                    refresh: async () => {},
                }}
            >
                <MemoryRouter>
                    <ArchivePage />
                </MemoryRouter>
            </Session.Provider>
        </QueryClientProvider>,
    );
}
async function selectRecord(
    user: ReturnType<typeof userEvent.setup>,
    name: string,
) {
    await user.click(screen.getByRole('combobox', { name: '현재 준비 공고' }));
    await user.click(screen.getByRole('option', { name }));
}

it('공고별 메모를 보존하고 복원·경험 연결 성공으로 작성 중 메모를 지우지 않는다', async () => {
    const user = userEvent.setup();
    mount();
    const note = await screen.findByRole('textbox');
    await user.type(note, 'A 메모');
    await selectRecord(user, 'B 공고');
    expect(note).toHaveValue('');
    await user.type(note, 'B 메모');
    await selectRecord(user, 'A 공고');
    expect(note).toHaveValue('A 메모');
    await user.click(screen.getByRole('button', { name: '내 준비로 복원' }));
    await screen.findByText('내 준비로 복원했습니다.');
    expect(note).toHaveValue('A 메모');
    await selectRecord(user, 'B 공고');
    await user.click(
        screen.getByRole('button', { name: '현재 건에 메모 연결' }),
    );
    await screen.findByText('현재 준비 건에 참고 메모를 연결했습니다.');
    expect(memberApi.reuse).toHaveBeenCalledWith('B', 'old');
    expect(note).toHaveValue('B 메모');
});

it('저장 중 다른 공고를 골라도 제출 대상과 메모는 고정되고 다른 초안은 남는다', async () => {
    let finish!: () => void;
    vi.mocked(memberApi.finish).mockReturnValue(
        new Promise<void>((resolve) => {
            finish = resolve;
        }),
    );
    const user = userEvent.setup();
    mount();
    const note = await screen.findByRole('textbox');
    await user.type(note, 'A 초안');
    await selectRecord(user, 'B 공고');
    await user.type(note, 'B 저장');
    await user.click(screen.getByRole('button', { name: '이 준비 건 보관' }));
    expect(note).toBeDisabled();
    await selectRecord(user, 'A 공고');
    expect(memberApi.finish).toHaveBeenCalledWith('B', 7, 'B 저장');
    await act(async () => finish());
    await waitFor(() => expect(note).toBeEnabled());
    expect(note).toHaveValue('A 초안');
    await selectRecord(user, 'B 공고');
    expect(note).toHaveValue('');
});

it('보관 실패 시 해당 메모를 유지하고 같은 대상의 revision으로 재시도한다', async () => {
    vi.mocked(memberApi.finish).mockRejectedValueOnce(new Error('저장 실패'));
    const user = userEvent.setup();
    mount();
    const note = await screen.findByRole('textbox');
    await user.type(note, '유지할 초안');
    await user.click(screen.getByRole('button', { name: '이 준비 건 보관' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('저장 실패');
    expect(note).toHaveValue('유지할 초안');
    await user.click(screen.getByRole('button', { name: '이 준비 건 보관' }));
    await screen.findByText('보관했습니다.');
    expect(memberApi.finish).toHaveBeenLastCalledWith('A', 2, '유지할 초안');
    expect(note).toHaveValue('');
});
