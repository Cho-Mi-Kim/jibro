import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import { demoRequest, resetDemo, notices } from '../features/housing/demo';
import { api } from '../features/housing/api';
// Exercise the real demo contract without accumulating artificial presentation delays.
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
function mount(path = '/') {
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
const elementScrollTo = Object.getOwnPropertyDescriptor(
    HTMLElement.prototype,
    'scrollTo',
);
afterEach(() => {
    if (elementScrollTo) {
        Object.defineProperty(
            HTMLElement.prototype,
            'scrollTo',
            elementScrollTo,
        );
    } else {
        Reflect.deleteProperty(HTMLElement.prototype, 'scrollTo');
    }
});
beforeEach(() => {
    HTMLDialogElement.prototype.showModal = function () {
        this.open = true;
    };
    HTMLDialogElement.prototype.close = function () {
        this.open = false;
        this.dispatchEvent(new Event('close'));
    };
    resetDemo();
    vi.stubGlobal('scrollTo', vi.fn());
    Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
        configurable: true,
        writable: true,
        value: vi.fn(),
    });
    vi.stubGlobal(
        'matchMedia',
        vi.fn((query: string) => ({
            matches: false,
            media: query,
            onchange: null,
            addListener: vi.fn(),
            removeListener: vi.fn(),
            addEventListener: vi.fn(),
            removeEventListener: vi.fn(),
            dispatchEvent: vi.fn(() => true),
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
describe('사용자 준비 흐름', () => {
    it('알림에서 이전을 누르면 검색어가 있는 공고 목록으로 돌아간다', async () => {
        await api.login('demo@example.invalid', 'demo');
        mount('/notices?keyword=청년&sort=newest');
        await userEvent.click(
            await screen.findByRole('link', { name: '알림 확인' }),
        );
        await userEvent.click(
            await screen.findByRole('button', { name: '이전 화면으로' }),
        );
        expect(
            await screen.findByRole('textbox', { name: '공고 검색' }),
        ).toHaveValue('청년');
    });
    it('내 준비의 조건 탭에서 연 알림은 같은 준비 건의 조건 탭으로 복귀한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/preparations');
        const nav = await screen.findByRole('navigation', {
            name: '개인 준비',
        });
        await userEvent.click(within(nav).getByRole('link', { name: '조건' }));
        await userEvent.click(
            await screen.findByRole('link', { name: '알림 확인' }),
        );
        await userEvent.click(
            await screen.findByRole('button', { name: '이전 화면으로' }),
        );
        const restored = within(
            await screen.findByRole('navigation', { name: '개인 준비' }),
        ).getByRole('link', { name: '조건' });
        expect(restored).toHaveAttribute('aria-current', 'page');
        expect(restored).toHaveAttribute(
            'href',
            '/preparations/' + p.id + '/conditions',
        );
    });
    it('알림 상단의 설정 버튼으로 팝업을 열고 저장한 설정을 다시 확인한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        mount('/alerts');
        const gear = await screen.findByRole('button', { name: '알림 설정' });
        expect(
            screen.queryByRole('link', { name: '알림 확인' }),
        ).not.toBeInTheDocument();
        expect(
            screen.queryByRole('link', { name: '내 계정' }),
        ).not.toBeInTheDocument();
        await userEvent.click(gear);
        const settings = within(
            await screen.findByRole('dialog', { name: '알림 설정' }),
        );
        const lead = await settings.findByRole('combobox', {
            name: '일정 사전 알림',
        });
        await userEvent.selectOptions(lead, '7');
        await userEvent.click(
            settings.getByRole('button', { name: '알림 설정 저장' }),
        );
        await settings.findByText('알림 설정을 저장했습니다.');
        await userEvent.click(settings.getByRole('button', { name: '닫기' }));
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        await userEvent.click(gear);
        expect(
            await screen.findByRole('combobox', { name: '일정 사전 알림' }),
        ).toHaveValue('7');
    });
    it('알림 주소를 직접 열면 이전 버튼이 마이로 이동한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        mount('/alerts');
        await userEvent.click(
            await screen.findByRole('button', { name: '이전 화면으로' }),
        );
        expect(
            await screen.findByRole('heading', { name: '마이' }),
        ).toBeVisible();
    });
    it('공고 카드를 클릭하거나 키보드로 펼치고 다른 카드를 선택하면 이전 카드를 접는다', async () => {
        mount('/notices');
        const first = await screen.findByRole('button', {
            name: notices[0].title,
        });
        const second = await screen.findByRole('button', {
            name: notices[1].title,
        });
        await userEvent.click(first);
        expect(first).toHaveAttribute('aria-expanded', 'true');
        const controls = document.querySelector('[data-notice-controls]');
        expect(controls).toHaveAttribute('data-collapsed', 'true');
        await userEvent.keyboard('{PageUp}');
        expect(controls).toHaveAttribute('data-collapsed', 'false');
        expect(
            await screen.findByRole('link', { name: '공고 자세히 보기' }),
        ).toHaveAttribute('href', '/notices/' + notices[0].id);
        await userEvent.click(second);
        expect(first).toHaveAttribute('aria-expanded', 'false');
        expect(second).toHaveAttribute('aria-expanded', 'true');
        expect(controls).toHaveAttribute('data-collapsed', 'true');
        await userEvent.keyboard('{Enter}');
        expect(second).toHaveAttribute('aria-expanded', 'false');
        expect(controls).toHaveAttribute('data-collapsed', 'false');
        expect(
            screen.queryByRole('region', { name: '공고 미리보기' }),
        ).not.toBeInTheDocument();
    });
    it('조건 결과 안내는 처음에 접히고 버튼을 다시 누르면 원래대로 접힌다', async () => {
        await api.login('demo@example.invalid', 'demo');
        await api.start('1', 1);
        mount('/preparations');
        const nav = await screen.findByRole('navigation', {
            name: '개인 준비',
        });
        await userEvent.click(within(nav).getByRole('link', { name: '조건' }));
        const toggle = await screen.findByLabelText('조건 확인 결과 안내');
        const note = screen.getByText(/입력한 내용에 대한 안내이며/);
        expect(note).not.toBeVisible();
        await userEvent.click(toggle);
        expect(note).toBeVisible();
        await userEvent.click(toggle);
        expect(note).not.toBeVisible();
        const source = screen.getAllByLabelText(/공식 근거 보기/)[0];
        const detail = source.closest('details')!;
        expect(detail).not.toHaveAttribute('open');
        await userEvent.click(source);
        expect(detail).toHaveAttribute('open');
        expect(screen.queryByText('답변 이어서 작성')).not.toBeInTheDocument();
        const resume = screen.getByRole('link', { name: '준비 이어하기' });
        expect(resume.closest('[role="region"]')).toBeNull();
        await userEvent.click(resume);
        expect(
            await screen.findByRole('group', {
                name: notices[0].questions[0].title,
            }),
        ).toBeVisible();
    });
    it('결과 카드에서 선택한 추가 확인 질문의 선택지를 바로 보여준다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const preparation = await api.start('1', 1);
        const target = preparation.questions[1];
        mount('/preparations/' + preparation.id + '/conditions/result');
        const card = (
            await screen.findByRole('heading', { name: target.title })
        ).closest('article');
        expect(card).not.toBeNull();
        await userEvent.click(
            within(card!).getByRole('link', { name: '답변 이어서 작성' }),
        );
        expect(
            await screen.findByRole('group', { name: target.title }),
        ).toBeVisible();
    });
    it.each([
        ['conditions', true],
        ['conditions/result', false],
    ] as const)(
        '주소를 직접 연 %s 화면은 이동 상태 없이 같은 레이아웃을 제공한다',
        async (section, inTab) => {
            await api.login('demo@example.invalid', 'demo');
            const p = await api.start('1', 1);
            mount('/preparations/' + p.id + '/' + section);
            await screen.findByRole('heading', { name: '추가로 확인할 조건' });
            if (inTab) {
                const navigation = screen.getByRole('navigation', {
                    name: '개인 준비',
                });
                expect(
                    within(navigation).getByRole('link', { name: '조건' }),
                ).toHaveAttribute('aria-current', 'page');
                expect(
                    screen.getByRole('link', { name: '준비 이어하기' }),
                ).toBeVisible();
                expect(
                    screen.queryByRole('heading', {
                        name: '내 조건 확인 결과',
                    }),
                ).not.toBeInTheDocument();
            } else {
                expect(
                    screen.getByRole('heading', { name: '내 조건 확인 결과' }),
                ).toBeVisible();
                expect(
                    screen.queryByRole('navigation', { name: '개인 준비' }),
                ).not.toBeInTheDocument();
                await userEvent.click(
                    screen.getByRole('link', { name: '이전 화면으로' }),
                );
                expect(
                    await screen.findByRole('heading', { name: p.title }),
                ).toBeVisible();
            }
        },
    );
    it('홈에서 질문을 마치고 결과의 이전 버튼을 누르면 선택 공고의 홈으로 돌아간다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/?notice=1');
        await userEvent.click(
            await screen.findByRole('link', { name: '준비 방법 보기' }),
        );
        await userEvent.click(
            await screen.findByRole('radio', { name: '확인했어요' }),
        );
        await screen.findByRole('group', {
            name: '본인에게 적용되는 무주택 기준을 확인했나요?',
        });
        await userEvent.click(
            await screen.findByRole('radio', { name: '해당하지 않아요' }),
        );
        await screen.findByRole('heading', { name: '내 조건 확인 결과' });
        const back = screen.getByRole('link', { name: '이전 화면으로' });
        expect(back).toHaveAttribute('href', '/?notice=1');
        await userEvent.click(back);
        expect(
            await screen.findByRole('link', { name: /질문 응답/ }),
        ).toHaveAttribute('href', '/preparations/' + p.id + '/conditions');
    });
    it('홈 여정의 공식 신청은 해당 준비 건의 신청 기록 팝업을 연다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        const save = vi.spyOn(api, 'schedule');
        try {
            mount('/');
            await userEvent.click(
                await screen.findByRole('link', { name: '공식 신청' }),
            );
            const dialog = within(
                await screen.findByRole('dialog', {
                    name: '신청을 완료하셨나요?',
                }),
            );
            expect(dialog.getByText(p.title)).toBeVisible();
            await userEvent.click(dialog.getByRole('button', { name: '닫기' }));
            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
            expect(save).not.toHaveBeenCalled();
            await userEvent.click(
                screen.getByRole('link', { name: '공식 신청' }),
            );
            expect(
                await screen.findByRole('dialog', {
                    name: '신청을 완료하셨나요?',
                }),
            ).toBeVisible();
        } finally {
            save.mockRestore();
        }
    });
    it('질문별 선택지를 자동 저장하고 다음 질문과 결과로 이동한다', async () => {
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
        await userEvent.click(
            await screen.findByRole('radio', { name: '해당하지 않아요' }),
        );
        await screen.findByRole('heading', { name: '내 조건 확인 결과' });
        await userEvent.click(screen.getByText('답변 완료 2개 보기'));
        expect(screen.getByText('경기도에 거주해요')).toBeVisible();
        expect((await api.preparation(p.id)).answers.residence).toBe(
            'GYEONGGI',
        );
    });
    it('자동 저장 실패 시 답변을 유지하고 다음 버튼으로 재시도한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        const write = vi
            .spyOn(api, 'answers')
            .mockRejectedValueOnce(new Error('저장 실패 테스트'));
        try {
            mount('/preparations/' + p.id + '/conditions/edit');
            await userEvent.click(
                await screen.findByRole('radio', { name: '잘 모르겠어요' }),
            );
            expect(await screen.findByRole('alert')).toHaveTextContent(
                '저장 실패 테스트',
            );
            expect(screen.getByRole('group')).toHaveAccessibleName(
                p.questions[0].title,
            );
            expect(
                screen.getByRole('radio', { name: '잘 모르겠어요' }),
            ).toBeChecked();
            await userEvent.click(screen.getByRole('button', { name: '다음' }));
            await waitFor(() =>
                expect(screen.getByRole('group')).toHaveAccessibleName(
                    p.questions[1].title,
                ),
            );
            expect(write).toHaveBeenCalledTimes(2);
            expect(
                (await api.preparation(p.id)).answers[p.questions[0].id],
            ).toBe('UNKNOWN');
            await userEvent.click(screen.getByRole('button', { name: '이전' }));
            expect(screen.getByRole('group')).toHaveAccessibleName(
                p.questions[0].title,
            );
            await userEvent.click(
                screen.getByRole('radio', { name: '잘 모르겠어요' }),
            );
            await waitFor(() =>
                expect(screen.getByRole('group')).toHaveAccessibleName(
                    p.questions[1].title,
                ),
            );
            expect(write).toHaveBeenCalledTimes(3);
        } finally {
            write.mockRestore();
        }
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
            screen.getByRole('heading', { name: /^서류 1개 중/ }),
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
        await userEvent.click(screen.getAllByText('상태·메모 수정')[0]);
        await userEvent.type(screen.getByRole('textbox'), '첫 번째 저장 메모');
        await userEvent.click(
            screen.getByRole('button', { name: /조건별 확인 서류/ }),
        );
        await userEvent.click(screen.getAllByText('상태·메모 수정')[1]);
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
            screen.getByRole('button', { name: '변경사항 저장' }),
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
            screen.getByRole('button', { name: '변경사항 저장' }),
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
    it('개인 기록을 읽지 못하면 현재 건과 저장을 유지하고 명시적 재시도 때만 전환한다', async () => {
        const user = await api.login('demo@example.invalid', 'demo');
        localStorage.setItem('jibro.active.' + user.id, 'original');
        const loadPreparations = api.preparations;
        let readFails = true;
        const read = vi
            .spyOn(api, 'preparations')
            .mockImplementation(() =>
                readFails
                    ? Promise.reject(new Error('노트 조회 실패'))
                    : loadPreparations(),
            );
        const write = vi.spyOn(api, 'start');
        try {
            mount('/notices/1');
            await userEvent.click(
                await screen.findByRole('button', { name: '내 조건 확인하기' }),
            );
            expect(await screen.findByRole('alert')).toHaveTextContent(
                '노트 조회 실패',
            );
            expect(write).not.toHaveBeenCalled();
            expect(localStorage.getItem('jibro.active.' + user.id)).toBe(
                'original',
            );
            expect(await loadPreparations()).toHaveLength(0);
            readFails = false;
            write.mockRejectedValueOnce(new Error('준비 저장 실패'));
            await userEvent.click(
                screen.getByRole('button', { name: '다시 시도' }),
            );
            expect(await screen.findByText(/준비 저장 실패/)).toBeVisible();
            expect(localStorage.getItem('jibro.active.' + user.id)).toBe(
                'original',
            );
            await userEvent.click(
                screen.getByRole('button', { name: '다시 시도' }),
            );
            await screen.findByRole('radio', { name: '잘 모르겠어요' });
            expect(write).toHaveBeenCalledTimes(2);
            expect(await loadPreparations()).toHaveLength(1);
            expect(localStorage.getItem('jibro.active.' + user.id)).not.toBe(
                'original',
            );
        } finally {
            read.mockRestore();
            write.mockRestore();
            localStorage.clear();
        }
    });
    it('시작 경로 단순 방문은 기록을 만들지 않는다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const write = vi.spyOn(api, 'start');
        try {
            mount('/notices/1/start');
            expect(
                await screen.findByRole('button', { name: '준비 시작' }),
            ).toBeVisible();
            expect(write).not.toHaveBeenCalled();
        } finally {
            write.mockRestore();
        }
    });
    it.each([0, 1])(
        '비회원이 선택한 공개 공고의 홈을 열어도 준비 기록을 만들지 않는다 (%i)',
        async (index) => {
            const write = vi.spyOn(api, 'start');
            const notice = notices[index];
            try {
                mount('/?notice=' + notice.id);
                expect(
                    await screen.findByRole('heading', { name: notice.title }),
                ).toBeVisible();
                if (!notice.canStart) {
                    expect(
                        screen.queryByRole('button', {
                            name: '이 공고로 준비 시작',
                        }),
                    ).not.toBeInTheDocument();
                }
                expect(write).not.toHaveBeenCalled();
            } finally {
                write.mockRestore();
                sessionStorage.clear();
            }
        },
    );
    it('선택한 공고에서 준비를 시작하면 그 준비 건의 홈을 연다', async () => {
        await api.login('demo@example.invalid', 'demo');
        try {
            mount('/?notice=1');
            await userEvent.click(
                await screen.findByRole('button', {
                    name: '이 공고로 준비 시작',
                }),
            );
            expect(
                await screen.findByRole('link', { name: '준비 방법 보기' }),
            ).toBeVisible();
            const records = await api.preparations();
            expect(records.map((p) => p.noticeId)).toEqual(['1']);
            expect(
                screen
                    .getByRole('heading', { name: records[0].title })
                    .closest('a'),
            ).toHaveAttribute('href', '/notices/' + records[0].noticeId);
            expect(
                screen.getByRole('link', { name: /질문 응답/ }),
            ).toHaveAttribute(
                'href',
                '/preparations/' + records[0].id + '/conditions',
            );
            await userEvent.click(
                screen.getByRole('link', { name: /질문 응답/ }),
            );
            const tabs = await screen.findByRole('navigation', {
                name: '개인 준비',
            });
            expect(
                within(tabs).getByRole('link', { name: '조건' }),
            ).toHaveAttribute('aria-current', 'page');
            expect(await screen.findByText(/진행 중 1건/)).toBeVisible();
        } finally {
            sessionStorage.clear();
            localStorage.clear();
        }
    });
    it('준비 탭을 바꿔도 열린 준비 선택기와 탭 줄은 유지되고 선택한 내용만 바뀐다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const preparation = await api.start('1', 1);
        mount('/preparations');
        const nav = await screen.findByRole('navigation', {
            name: '개인 준비',
        });
        const selector = screen.getByText(/진행 중/).closest('details')!;
        await userEvent.click(within(selector).getByText(/진행 중/));
        for (const [label, heading] of [
            ['조건', '추가로 확인할 조건'],
            ['서류', '공통 필수'],
            ['일정', '전체 일정'],
            ['개요', preparation.title],
        ]) {
            const link = within(nav).getByRole('link', { name: label });
            await userEvent.click(link);
            const panel = screen.getByRole('region', {
                name: '선택한 준비 탭 내용',
            });
            await within(panel).findByRole('heading', { name: heading });
            expect(
                within(panel).queryByRole('link', { name: '이전 화면으로' }),
            ).not.toBeInTheDocument();
            expect(link).toHaveAttribute('aria-current', 'page');
            expect(screen.getByRole('navigation', { name: '개인 준비' })).toBe(
                nav,
            );
            expect(screen.getByText(/진행 중/).closest('details')).toBe(
                selector,
            );
            expect(selector).toHaveAttribute('open');
            expect(within(selector).getByRole('combobox')).toHaveValue(
                preparation.id,
            );
        }
    });
    it('서류 탭에서 준비 건 변경을 취소하면 메모를 보존하고, 승인하면 같은 탭의 다른 건을 연다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const first = await api.start('1', 1);
        const second = { ...first, id: 'second-record', title: '다른 준비 건' };
        const list = vi
            .spyOn(api, 'preparations')
            .mockResolvedValue([first, second]);
        const detail = vi
            .spyOn(api, 'preparation')
            .mockImplementation(async (id) =>
                id === second.id ? second : first,
            );
        const confirm = vi
            .spyOn(window, 'confirm')
            .mockReturnValueOnce(false)
            .mockReturnValue(true);
        try {
            mount('/preparations/' + first.id + '/documents');
            await userEvent.click(await screen.findByText('상태·메모 수정'));
            await userEvent.type(screen.getByRole('textbox'), '작성 중');
            await userEvent.click(
                screen.getByText(/진행 중/).closest('summary')!,
            );
            const selector = screen.getByRole('combobox', {
                name: '준비 건 선택',
            });
            await userEvent.selectOptions(selector, second.id);
            expect(confirm).toHaveBeenCalledTimes(1);
            expect(selector).toHaveValue(first.id);
            expect(screen.getByRole('textbox')).toHaveValue('작성 중');
            await userEvent.selectOptions(selector, second.id);
            await screen.findByRole('heading', { name: second.title });
            expect(selector).toHaveValue(second.id);
            const nav = screen.getByRole('navigation', { name: '개인 준비' });
            expect(
                within(nav).getByRole('link', { name: '서류' }),
            ).toHaveAttribute('aria-current', 'page');
        } finally {
            confirm.mockRestore();
            detail.mockRestore();
            list.mockRestore();
        }
    });
    it('내 준비에서 선택을 바꾸면 준비도와 모든 관리 경로가 해당 건으로 바뀐다', async () => {
        const user = await api.login('demo@example.invalid', 'demo');
        const original = await api.start('1', 1);
        const first = {
            ...original,
            title: '첫 번째 신청 준비',
            progress: 25,
            houseStage: 2,
            nextAction: {
                ...original.nextAction,
                path: '/preparations/' + original.id + '/documents',
            },
        };
        const second = {
            ...first,
            id: 'another-preparation',
            title: '두 번째 신청 준비',
            progress: 80,
            houseStage: 4,
            nextAction: {
                ...first.nextAction,
                path: '/preparations/another-preparation/schedule',
            },
        };
        const read = vi
            .spyOn(api, 'preparations')
            .mockResolvedValue([first, second]);
        try {
            localStorage.setItem('jibro.active.' + user.id, first.id);
            mount('/preparations');
            expect(
                await screen.findByRole('heading', { name: first.title }),
            ).toBeVisible();
            async function expectSelected(record: typeof first) {
                expect(
                    screen.getByRole('progressbar', { name: '준비도' }),
                ).toHaveAttribute('value', String(record.progress));
                const navigation = screen.getByRole('navigation', {
                    name: '개인 준비',
                });
                for (const [label, suffix] of [
                    ['조건', 'conditions'],
                    ['서류', 'documents'],
                    ['일정', 'schedule'],
                ]) {
                    expect(
                        within(navigation).getByRole('link', {
                            name: label,
                        }),
                    ).toHaveAttribute(
                        'href',
                        '/preparations/' + record.id + '/' + suffix,
                    );
                }
                await userEvent.click(
                    screen.getByRole('button', { name: '신청 여부 기록' }),
                );
                const dialog = within(
                    screen.getByRole('dialog', {
                        name: '신청을 완료하셨나요?',
                    }),
                );
                expect(dialog.getByText(record.title)).toBeVisible();
                await userEvent.click(
                    dialog.getByRole('button', { name: '닫기' }),
                );
                expect(
                    screen.getByRole('link', { name: /준비 계속하기/ }),
                ).toHaveAttribute('href', record.nextAction.path);
            }
            await expectSelected(first);
            await userEvent.click(
                screen.getByText(/진행 중/).closest('summary')!,
            );
            await userEvent.selectOptions(
                screen.getByRole('combobox', { name: '준비 건 선택' }),
                second.id,
            );
            expect(
                await screen.findByRole('heading', { name: second.title }),
            ).toBeVisible();
            await expectSelected(second);
            expect(localStorage.getItem('jibro.active.' + user.id)).toBe(
                second.id,
            );
            expect(
                screen.getByRole('link', { name: '완료 여정 보관함' }),
            ).toHaveAttribute('href', '/archive');
            await userEvent.selectOptions(
                screen.getByRole('combobox', { name: '준비 건 선택' }),
                first.id,
            );
            expect(
                await screen.findByRole('heading', { name: first.title }),
            ).toBeVisible();
            await expectSelected(first);
        } finally {
            read.mockRestore();
            localStorage.clear();
        }
    });
    it('선택한 준비 건을 홈에서 유지하고 다른 공고 미리보기는 선택을 바꾸지 않는다', async () => {
        const user = await api.login('demo@example.invalid', 'demo');
        const first = await api.start('1', 1);
        const second = {
            ...first,
            id: 'another-record',
            title: '두 번째 준비 건',
        };
        const read = vi
            .spyOn(api, 'preparations')
            .mockResolvedValue([first, second]);
        try {
            localStorage.setItem('jibro.active.' + user.id, second.id);
            mount('/');
            expect(
                await screen.findByRole('heading', { name: second.title }),
            ).toBeVisible();
            await userEvent.click(
                within(
                    screen.getByRole('navigation', { name: '주 메뉴' }),
                ).getByRole('link', { name: '공고' }),
            );
            const other = (
                await screen.findByRole('button', {
                    name: '[시연] 청년 주거 준비 예시',
                })
            ).closest('article')!;
            await userEvent.click(
                within(other).getByRole('button', {
                    name: '[시연] 청년 주거 준비 예시',
                }),
            );
            await userEvent.click(
                await within(other).findByRole('link', {
                    name: '공고 자세히 보기',
                }),
            );
            await userEvent.click(
                await screen.findByRole('tab', { name: '일정' }),
            );
            expect(
                await screen.findByRole('tab', { name: '일정' }),
            ).toHaveAttribute('aria-selected', 'true');
            expect(localStorage.getItem('jibro.active.' + user.id)).toBe(
                second.id,
            );
            await userEvent.click(
                within(
                    screen.getByRole('navigation', { name: '주 메뉴' }),
                ).getByRole('link', { name: '홈' }),
            );
            expect(
                await screen.findByRole('heading', { name: second.title }),
            ).toBeVisible();
        } finally {
            read.mockRestore();
            localStorage.clear();
        }
    });
    it('로그인 복귀부터 모름 응답 저장과 서류 실패 후 재시도까지', async () => {
        mount('/notices/1');
        await userEvent.click(
            await screen.findByRole('button', { name: '내 조건 확인하기' }),
        );
        await userEvent.click(
            await screen.findByRole('button', { name: '시연 사용자로 보기' }),
        );
        const unknown = await screen.findByRole('radio', {
            name: '잘 모르겠어요',
        });
        expect(unknown).not.toBeChecked();
        await userEvent.click(unknown);
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
        expect(
            await screen.findByRole('heading', { name: '내 조건 확인 결과' }),
        ).toBeInTheDocument();
        expect(screen.getByText('추가 확인 필요')).toBeInTheDocument();
        await userEvent.click(
            screen.getByRole('link', { name: '내 준비 목록 보기' }),
        );
        await screen.findByRole('button', { name: /거주 확인 서류/ });
        await userEvent.click(screen.getAllByText('상태·메모 수정')[0]);
        await userEvent.selectOptions(
            screen.getByRole('combobox', { name: '준비 상태' }),
            'READY',
        );
        const save = vi
            .spyOn(api, 'document')
            .mockRejectedValueOnce(new Error('시연 저장 실패'));
        await userEvent.click(
            screen.getAllByRole('button', {
                name: '변경사항 저장',
            })[0],
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            '시연 저장 실패',
        );
        expect(screen.getByText(/준비 완료 0개/)).toBeInTheDocument();
        save.mockRestore();
        await userEvent.click(
            screen.getAllByRole('button', {
                name: '변경사항 저장',
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
            await screen.findByRole('button', {
                name: '[시연] 청년 주거 준비 예시',
            }),
        ).toBeInTheDocument();
    });
    it('필터 시트의 초안은 닫으면 폐기하고 적용하면 복수 선택과 정렬을 유지하며 첫 페이지로 돌아간다', async () => {
        const read = vi.spyOn(api, 'notices');
        const lastParams = () =>
            new URLSearchParams(read.mock.calls.at(-1)?.[0]);
        try {
            mount('/notices?region=서울&sort=newest&page=2');
            await screen.findByText('조건에 맞는 공고가 없어요');
            await userEvent.click(screen.getByRole('button', { name: /지역/ }));
            let dialog = within(
                screen.getByRole('dialog', { name: '지역 필터' }),
            );
            await userEvent.click(
                await dialog.findByRole('checkbox', { name: '경기' }),
            );
            await userEvent.click(dialog.getByRole('button', { name: '닫기' }));
            expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
            expect(lastParams().getAll('region')).toEqual(['서울']);
            expect(lastParams().get('page')).toBe('2');

            await userEvent.click(screen.getByRole('button', { name: /지역/ }));
            dialog = within(screen.getByRole('dialog', { name: '지역 필터' }));
            expect(
                dialog.getByRole('checkbox', { name: '서울' }),
            ).toBeChecked();
            expect(
                dialog.getByRole('checkbox', { name: '경기' }),
            ).not.toBeChecked();
            await userEvent.click(
                dialog.getByRole('checkbox', { name: '경기' }),
            );
            await userEvent.click(
                dialog.getByRole('button', { name: /^적용$/ }),
            );
            await waitFor(() =>
                expect(lastParams().getAll('region')).toEqual(['서울', '경기']),
            );
            expect(lastParams().get('page')).toBe('0');
            expect(lastParams().get('sort')).toBe('newest');
            expect(
                await screen.findByRole('button', {
                    name: notices[0].title,
                }),
            ).toBeVisible();
            expect(
                await screen.findByRole('button', {
                    name: notices[1].title,
                }),
            ).toBeVisible();
            expect(
                screen.getByRole('button', { name: '서울 필터 해제' }),
            ).toBeVisible();
            expect(
                screen.getByRole('button', { name: '경기 필터 해제' }),
            ).toBeVisible();

            await userEvent.click(
                screen.getByRole('button', { name: '서울 필터 해제' }),
            );
            await waitFor(() =>
                expect(lastParams().getAll('region')).toEqual(['경기']),
            );
            await waitFor(() =>
                expect(
                    screen.queryByRole('button', {
                        name: notices[0].title,
                    }),
                ).not.toBeInTheDocument(),
            );
            expect(
                await screen.findByRole('button', {
                    name: notices[1].title,
                }),
            ).toBeVisible();
        } finally {
            read.mockRestore();
        }
    });
    it('서류 완료를 먼저 표시하고 접힌 메뉴에서 상태를 다시 변경할 수 있다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/preparations/' + p.id + '/documents');
        const ready = await screen.findByRole('button', { name: '준비 완료' });
        const options = screen.getByText('상태·메모 수정').closest('details')!;
        expect(options).not.toHaveAttribute('open');
        expect(
            ready.compareDocumentPosition(options) &
                Node.DOCUMENT_POSITION_FOLLOWING,
        ).toBeTruthy();
        await userEvent.click(ready);
        expect(
            await screen.findByText('이 서류의 준비 기록을 저장했어요.'),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: '준비 완료' }),
        ).toBeDisabled();
        expect((await api.preparation(p.id)).documents[0].state).toBe('READY');
        expect(options).not.toHaveAttribute('open');
        await userEvent.click(screen.getByText('상태·메모 수정'));
        await userEvent.selectOptions(
            screen.getByRole('combobox', { name: '준비 상태' }),
            'IN_PROGRESS',
        );
        await userEvent.click(
            screen.getByRole('button', { name: '변경사항 저장' }),
        );
        await waitFor(async () =>
            expect((await api.preparation(p.id)).documents[0].state).toBe(
                'IN_PROGRESS',
            ),
        );
        expect(screen.getByRole('button', { name: '준비 완료' })).toBeEnabled();
    });
    it('일정 탭에서 신청 기록 카드를 표시하지 않는다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/preparations/' + p.id + '/schedule');
        await screen.findByRole('heading', { name: '전체 일정' });
        expect(
            screen.queryByRole('heading', { name: '나의 신청 기록' }),
        ).not.toBeInTheDocument();
        expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
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
