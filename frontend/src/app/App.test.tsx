import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import { demoRequest, resetDemo, notices } from '../features/housing/demo';
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
    it('질문별 선택지를 저장하고 결과에서 선택한 답변을 보여준다', async () => {
        notices[0].questions[0].options = [
            { value: 'SEOUL', label: '서울특별시에 거주해요' },
            { value: 'GYEONGGI', label: '경기도에 거주해요' },
            { value: 'UNKNOWN', label: '기준일의 거주지를 모르겠어요' },
        ];
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/preparations/' + p.id + '/conditions/edit');
        await userEvent.click(
            await screen.findByRole('radio', { name: '경기도에 거주해요' }),
        );
        expect(
            screen.queryByRole('radio', { name: '확인했어요' }),
        ).not.toBeInTheDocument();
        await userEvent.click(
            screen.getByRole('button', { name: '저장 후 다음' }),
        );
        await userEvent.click(
            await screen.findByRole('radio', { name: '해당하지 않아요' }),
        );
        await userEvent.click(
            screen.getByRole('button', { name: '저장 후 결과 보기' }),
        );
        await screen.findByRole('heading', { name: '내 조건 확인 결과' });
        await userEvent.click(screen.getByText('답변 완료 2개 보기'));
        expect(screen.getByText('경기도에 거주해요')).toBeVisible();
        expect((await api.preparation(p.id)).answers.residence).toBe(
            'GYEONGGI',
        );
    });
    it('해당 없는 서류는 목록에서 숨긴다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        await api.answers(p.id, p.revision, { residence: 'YES', house: 'NO' });
        mount('/preparations/' + p.id + '/documents');
        await screen.findByRole('button', { name: /거주 확인 서류/ });
        expect(
            screen.queryByRole('button', { name: /조건별 확인 서류/ }),
        ).not.toBeInTheDocument();
        expect(screen.queryByText('조건별 확인 서류')).not.toBeInTheDocument();
        expect(
            screen.getByRole('heading', { name: /준비 대상 서류 1개/ }),
        ).toBeVisible();
    });
    it('미확정 서류는 준비 목록 대신 추가 확인 안내에만 표시한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/preparations/' + p.id + '/documents');
        await screen.findByRole('button', { name: /거주 확인 서류/ });
        expect(
            screen.queryByRole('button', { name: /조건별 확인 서류/ }),
        ).not.toBeInTheDocument();
        expect(
            screen.getByRole('heading', { name: '해당 여부 추가 확인 1개' }),
        ).toBeVisible();
        expect(
            screen.getByRole('link', { name: '조건 답변 보완하기' }),
        ).toBeVisible();
    });
    it('한 서류 저장 후 다른 서류의 미저장 입력을 유지한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        await api.answers(p.id, p.revision, { residence: 'YES', house: 'YES' });
        mount('/preparations/' + p.id + '/documents');
        await screen.findByRole('button', { name: /거주 확인 서류/ });
        await userEvent.click(screen.getAllByText('준비 상태와 메모 수정')[0]);
        await userEvent.type(screen.getByRole('textbox'), '첫 번째 저장 메모');
        await userEvent.click(
            screen.getByRole('button', { name: /조건별 확인 서류/ }),
        );
        await userEvent.click(screen.getAllByText('준비 상태와 메모 수정')[1]);
        await userEvent.type(
            screen.getByRole('textbox'),
            '두 번째 작성 중 메모',
        );
        await userEvent.selectOptions(
            screen.getByRole('combobox', { name: '준비 상태' }),
            'IN_PROGRESS',
        );
        await userEvent.click(
            screen.getByRole('button', { name: /거주 확인 서류/ }),
        );
        expect(screen.getByRole('textbox')).toHaveValue('첫 번째 저장 메모');
        await userEvent.click(
            screen.getByRole('button', { name: '이 서류 준비 상태 저장' }),
        );
        await waitFor(async () =>
            expect((await api.preparation(p.id)).documents[0].note).toBe(
                '첫 번째 저장 메모',
            ),
        );
        await userEvent.click(
            screen.getByRole('button', { name: /조건별 확인 서류/ }),
        );
        expect(screen.getByRole('textbox')).toHaveValue('두 번째 작성 중 메모');
        expect(screen.getByRole('combobox', { name: '준비 상태' })).toHaveValue(
            'IN_PROGRESS',
        );
        await userEvent.click(
            screen.getByRole('button', { name: '이 서류 준비 상태 저장' }),
        );
        await waitFor(async () =>
            expect((await api.preparation(p.id)).documents[1].note).toBe(
                '두 번째 작성 중 메모',
            ),
        );
    });
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
        await screen.findByRole('button', { name: /거주 확인 서류/ });
        await userEvent.click(screen.getAllByText('준비 상태와 메모 수정')[0]);
        await userEvent.selectOptions(
            screen.getByRole('combobox', { name: '준비 상태' }),
            'READY',
        );
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
    it('서류 완료 바로 기록 후 준비도와 상태가 갱신된다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/preparations/' + p.id + '/documents');
        await userEvent.click(
            await screen.findByRole('button', {
                name: '이 서류 준비 완료로 기록',
            }),
        );
        expect(
            await screen.findByText('이 서류의 준비 기록을 저장했어요.'),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: '준비 완료로 기록했어요' }),
        ).toBeDisabled();
        expect((await api.preparation(p.id)).documents[0].state).toBe('READY');
    });
    it('신청 여부 기록을 저장하고 다시 취소할 수 있다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/preparations/' + p.id + '/schedule');
        await userEvent.click(
            await screen.findByRole('button', { name: '신청 여부 기록' }),
        );
        await userEvent.click(screen.getByRole('checkbox'));
        await waitFor(async () =>
            expect((await api.preparation(p.id)).schedules[0].recorded).toBe(
                true,
            ),
        );
        await userEvent.click(screen.getByRole('checkbox'));
        await waitFor(async () =>
            expect((await api.preparation(p.id)).schedules[0].recorded).toBe(
                false,
            ),
        );
    });
    it('마이에서 로그아웃한 뒤 내 준비 접근은 로그인을 요구한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        mount('/my');
        await userEvent.click(
            await screen.findByRole('button', { name: '로그아웃' }),
        );
        await screen.findByRole('heading', { name: /나의 첫 집을 향해/ });
        await userEvent.click(
            screen.getAllByRole('link', { name: '내 준비' })[0],
        );
        expect(
            await screen.findByRole('heading', { name: '로그인' }),
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
