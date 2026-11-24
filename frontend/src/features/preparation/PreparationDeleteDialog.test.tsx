import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PreparationDeleteDialog } from './PreparationDeleteDialog';
import { preparationDeletionApi } from './preparationDeletionApi';
const navigate = vi.hoisted(() => vi.fn());
vi.mock('react-router', () => ({ useNavigate: () => navigate }));
vi.mock('../../shared/api/transport', () => ({
    demoMode: false,
    call: vi.fn(),
}));
vi.mock('../auth/useSession', () => ({
    useSession: () => ({ user: { id: 'owner' } }),
}));

beforeEach(() => {
    navigate.mockReset();
    localStorage.clear();
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
    vi.spyOn(preparationDeletionApi, 'details').mockResolvedValue({
        title: '내 준비 공고',
        revision: 4,
        trial: false,
        passwordLogin: true,
        mailMode: 'smtp',
    });
    vi.spyOn(preparationDeletionApi, 'delete').mockResolvedValue(undefined);
    vi.spyOn(preparationDeletionApi, 'proof').mockResolvedValue(undefined);
});
afterEach(() => {
    vi.restoreAllMocks();
    Reflect.deleteProperty(HTMLDialogElement.prototype, 'showModal');
    Reflect.deleteProperty(HTMLDialogElement.prototype, 'close');
});
function mount(afterDeleteTo?: string) {
    const client = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
        },
    });
    client.setQueryData(
        ['preparations', 'owner'],
        [{ id: 'one' }, { id: 'other' }],
    );
    client.setQueryData(['preparation', 'owner', 'one'], { id: 'one' });
    client.setQueryData(['preparation-history-detail', 'owner', 'one', 1], {
        private: 'answer',
    });
    render(
        <QueryClientProvider client={client}>
            <PreparationDeleteDialog id="one" afterDeleteTo={afterDeleteTo} />
        </QueryClientProvider>,
    );
    return client;
}
async function open() {
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '준비 기록 삭제' }));
    await screen.findByText('내 준비');
    return user;
}
describe('preparation deletion confirmation', () => {
    it('requires explicit phrase and password, then clears only the selected record and history', async () => {
        localStorage.setItem('jibro.active.owner', 'one');
        const client = mount('/preparations');
        const user = await open();
        const submit = screen.getByRole('button', { name: '영구 삭제' });
        expect(submit).toBeDisabled();
        await user.type(screen.getByLabelText('현재 비밀번호'), 'password');
        expect(submit).toBeDisabled();
        await user.type(
            screen.getByLabelText('확인을 위해 ‘준비 삭제’를 입력해 주세요.'),
            '준비 삭제',
        );
        await user.click(submit);
        await waitFor(() =>
            expect(navigate).toHaveBeenCalledWith('/preparations', {
                replace: true,
            }),
        );
        expect(preparationDeletionApi.delete).toHaveBeenCalledWith('one', {
            revision: 4,
            confirmation: '준비 삭제',
            password: 'password',
        });
        expect(localStorage.getItem('jibro.active.owner')).toBeNull();
        expect(client.getQueryData(['preparations', 'owner'])).toEqual([
            { id: 'other' },
        ]);
        expect(
            client.getQueryData(['preparation', 'owner', 'one']),
        ).toBeUndefined();
        expect(
            client.getQueryData([
                'preparation-history-detail',
                'owner',
                'one',
                1,
            ]),
        ).toBeUndefined();
    });
    it('cancel does not delete and credentials are discarded when reopening', async () => {
        mount();
        const user = await open();
        await user.type(screen.getByLabelText('현재 비밀번호'), 'password');
        await user.click(screen.getByRole('button', { name: '취소' }));
        await user.click(
            screen.getByRole('button', { name: '준비 기록 삭제' }),
        );
        expect(screen.getByLabelText('현재 비밀번호')).toHaveValue('');
        expect(preparationDeletionApi.delete).not.toHaveBeenCalled();
    });
    it('offers mail proof bound to the loaded revision and never submits a password with it', async () => {
        mount();
        const user = await open();
        await user.click(screen.getByLabelText('이메일 확인 코드로 인증'));
        await user.click(
            screen.getByRole('button', { name: '확인 코드 받기' }),
        );
        await screen.findByText('가입한 이메일로 삭제 확인 코드를 보냈습니다.');
        expect(preparationDeletionApi.proof).toHaveBeenCalledWith('one', 4);
        await user.type(
            screen.getByLabelText('삭제 확인 코드'),
            'mailed-proof',
        );
        await user.type(
            screen.getByLabelText('확인을 위해 ‘준비 삭제’를 입력해 주세요.'),
            '준비 삭제',
        );
        await user.click(screen.getByRole('button', { name: '영구 삭제' }));
        await waitFor(() =>
            expect(preparationDeletionApi.delete).toHaveBeenCalledWith('one', {
                revision: 4,
                confirmation: '준비 삭제',
                code: 'mailed-proof',
            }),
        );
        expect(navigate).not.toHaveBeenCalled();
    });
    it('shows conflicts without clearing saved data or dismissing the confirmation', async () => {
        vi.mocked(preparationDeletionApi.delete).mockRejectedValue(
            new Error('기준이 변경되었습니다.'),
        );
        const client = mount();
        const user = await open();
        await user.type(screen.getByLabelText('현재 비밀번호'), 'password');
        await user.type(
            screen.getByLabelText('확인을 위해 ‘준비 삭제’를 입력해 주세요.'),
            '준비 삭제',
        );
        await user.click(screen.getByRole('button', { name: '영구 삭제' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            '기준이 변경되었습니다.',
        );
        expect(screen.getByRole('dialog')).toBeVisible();
        expect(client.getQueryData(['preparation', 'owner', 'one'])).toEqual({
            id: 'one',
        });
    });
    it('requires explicit confirmation even when no extra credential is required for a trial owner', async () => {
        vi.mocked(preparationDeletionApi.details).mockResolvedValue({
            title: '내 준비 공고',
            revision: 4,
            trial: true,
            passwordLogin: false,
            mailMode: 'unavailable',
        });
        mount();
        const user = await open();
        const dialog = screen.getByRole('dialog');
        expect(within(dialog).queryByLabelText('현재 비밀번호')).toBeNull();
        expect(
            within(dialog).getByRole('button', { name: '영구 삭제' }),
        ).toBeDisabled();
        await user.type(
            screen.getByLabelText('확인을 위해 ‘준비 삭제’를 입력해 주세요.'),
            '준비 삭제',
        );
        await user.click(screen.getByRole('button', { name: '영구 삭제' }));
        await waitFor(() =>
            expect(preparationDeletionApi.delete).toHaveBeenCalledWith('one', {
                revision: 4,
                confirmation: '준비 삭제',
            }),
        );
    });
});
