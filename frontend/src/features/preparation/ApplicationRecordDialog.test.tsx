import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { api } from '../housing/api';
import type { Preparation } from '../housing/types';
import { ApplicationRecordDialog } from './ApplicationRecordDialog';

const saved = vi.hoisted(() => vi.fn());
vi.mock('./usePreparation', () => ({ useSaved: () => saved }));

const evidence = { label: '공고문 일정', url: null, checkedAt: '2026-11-06' };
const application = {
    id: 'application',
    title: '신청 접수 마감 (18:00, 조기 중단 가능)',
    date: '2026-12-31',
    recorded: false,
    evidence,
};
const preparation: Preparation = {
    id: 'selected-preparation',
    noticeId: 'notice',
    title: '선택한 청년 전세임대 모집',
    version: 3,
    revision: 7,
    guideState: 'AVAILABLE',
    questions: [],
    answers: {},
    documents: [],
    schedules: [
        { ...application, id: 'documents', title: '서류 제출 마감' },
        application,
    ],
    progress: 0,
    houseStage: 0,
    warnings: [],
    nextAction: { label: '', reason: '', path: '' },
};

const dialogMethods = {
    showModal: Object.getOwnPropertyDescriptor(
        HTMLDialogElement.prototype,
        'showModal',
    ),
    close: Object.getOwnPropertyDescriptor(
        HTMLDialogElement.prototype,
        'close',
    ),
};
beforeEach(() => {
    saved.mockReset();
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
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
});
afterEach(() => {
    vi.restoreAllMocks();
    for (const method of ['showModal', 'close'] as const) {
        const descriptor = dialogMethods[method];
        if (descriptor)
            Object.defineProperty(
                HTMLDialogElement.prototype,
                method,
                descriptor,
            );
        else Reflect.deleteProperty(HTMLDialogElement.prototype, method);
    }
});

function mount(overrides: Partial<Preparation> = {}) {
    render(
        <QueryClientProvider
            client={
                new QueryClient({
                    defaultOptions: { mutations: { retry: false } },
                })
            }
        >
            <ApplicationRecordDialog
                preparation={{ ...preparation, ...overrides }}
            />
        </QueryClientProvider>,
    );
}
async function open() {
    await userEvent.click(
        screen.getByRole('button', { name: '신청 여부 기록' }),
    );
    return within(screen.getByRole('dialog', { name: '신청을 완료하셨나요?' }));
}

describe('신청 여부 기록 팝업', () => {
    it('열고 닫기만 하면 신청 기록을 변경하지 않는다', async () => {
        const update = vi.spyOn(api, 'schedule');
        mount();
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        const dialog = await open();
        expect(dialog.getByText(preparation.title)).toBeVisible();
        await userEvent.click(dialog.getByRole('button', { name: '닫기' }));
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(update).not.toHaveBeenCalled();
    });

    it.each([
        ['완료했어요', true],
        ['아직이에요', false],
    ] as const)(
        '%s를 누르면 선택한 준비 건의 신청 일정만 저장한다',
        async (label, recorded) => {
            const next = { ...preparation, revision: 8 };
            const update = vi.spyOn(api, 'schedule').mockResolvedValue(next);
            mount();
            const dialog = await open();
            await userEvent.click(dialog.getByRole('button', { name: label }));
            await waitFor(() => expect(saved).toHaveBeenCalledWith(next));
            expect(update).toHaveBeenCalledExactlyOnceWith(
                preparation.id,
                application.id,
                7,
                recorded,
            );
            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        },
    );

    it('저장 오류는 팝업에서 알려주고 완료로 표시하지 않는다', async () => {
        vi.spyOn(api, 'schedule').mockRejectedValue(
            new Error('저장하지 못했습니다.'),
        );
        mount();
        const dialog = await open();
        await userEvent.click(
            dialog.getByRole('button', { name: '완료했어요' }),
        );
        expect(await dialog.findByRole('alert')).toHaveTextContent(
            '저장하지 못했습니다.',
        );
        expect(screen.getByRole('dialog')).toBeVisible();
        expect(saved).not.toHaveBeenCalled();
    });

    it('저장 실패 후 여정 경로로 다시 열면 이전 오류를 지우고 현재 신청을 기록한다', async () => {
        const update = vi
            .spyOn(api, 'schedule')
            .mockRejectedValueOnce(new Error('저장하지 못했습니다.'))
            .mockResolvedValue({ ...preparation, revision: 8 });
        const client = new QueryClient({
            defaultOptions: { mutations: { retry: false } },
        });
        const view = (requested: boolean) => (
            <QueryClientProvider client={client}>
                <ApplicationRecordDialog
                    preparation={preparation}
                    requested={requested}
                />
            </QueryClientProvider>
        );
        const { rerender } = render(view(false));
        const manual = await open();
        await userEvent.click(
            manual.getByRole('button', { name: '완료했어요' }),
        );
        await manual.findByRole('alert');
        await userEvent.click(manual.getByRole('button', { name: '닫기' }));
        rerender(view(true));
        const reopened = within(
            await screen.findByRole('dialog', { name: '신청을 완료하셨나요?' }),
        );
        expect(reopened.queryByRole('alert')).not.toBeInTheDocument();
        expect(reopened.getByText(preparation.title)).toBeVisible();
        await userEvent.click(
            reopened.getByRole('button', { name: '완료했어요' }),
        );
        await waitFor(() =>
            expect(saved).toHaveBeenCalledWith({ ...preparation, revision: 8 }),
        );
        expect(update).toHaveBeenLastCalledWith(
            preparation.id,
            application.id,
            7,
            true,
        );
    });

    it.each([
        { guideState: 'REVIEWING' as const },
        { schedules: [preparation.schedules[0]] },
    ])(
        '기록 불가능한 준비 건에는 저장 동작을 제공하지 않는다',
        async (overrides) => {
            const update = vi.spyOn(api, 'schedule');
            mount(overrides);
            const dialog = await open();
            for (const label of ['완료했어요', '아직이에요']) {
                const action = dialog.queryByRole('button', { name: label });
                if (action) expect(action).toBeDisabled();
            }
            expect(update).not.toHaveBeenCalled();
        },
    );
});
