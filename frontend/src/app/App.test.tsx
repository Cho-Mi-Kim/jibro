import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import { demoRequest, resetDemo, notices } from '../features/housing/demo';
import { demoNoticeListItem } from '../features/housing/demoNoticeListItem';
import { api } from '../features/housing/api';
import { memberApi } from '../features/member/memberApi';
import * as scrolling from '../shared/ui/animateScroll';
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
    it('일회성 결과는 해당 조건·서류와 공통 안내만 보이고 수정한 답변의 미확인 항목도 유지한다', async () => {
        const evidence = notices[0].evidence;
        notices[0].questions = [
            {
                id: 'common',
                title: '공통 거주 조건',
                reason: '공고 기준일의 거주지를 확인해요.',
                evidence,
                options: [{ value: 'YES', label: '거주 조건을 확인했어요' }],
            },
            {
                id: 'address',
                title: '주소 변경 서류 제출 대상',
                reason: '공고일 이후 주소가 바뀌었다면 증빙을 준비해요.',
                evidence,
                options: [
                    { value: 'YES', label: '주소가 바뀌었어요' },
                    { value: 'NO', label: '주소가 바뀌지 않았어요' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
            {
                id: 'work',
                title: '산업단지 추가 조건',
                reason: '산업단지 근로자로 신청할 때 확인해요.',
                evidence,
                options: [
                    { value: 'MEETS', label: '근로자 유형으로 신청해요' },
                    { value: 'NOT_MY_TYPE', label: '다른 유형으로 신청해요' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
            {
                id: 'guide',
                title: '공통 계약 준비 안내',
                reason: '공식 계약 일정을 확인해요.',
                evidence,
                options: [
                    { value: 'ACK', label: '안내를 확인했어요' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
        ];
        notices[0].documentTemplates = [
            { id: 'common', title: '공통 확인 서류' },
            {
                id: 'address',
                title: '주소 변경 증명서',
                conditionQuestionId: 'address',
                conditionAnswerValues: ['YES'],
            },
            {
                id: 'work',
                title: '근로자 증빙',
                conditionQuestionId: 'work',
                conditionAnswerValues: ['MEETS'],
            },
        ].map((document) => ({
            ...document,
            method: '공식 서류를 준비해요.',
            issuer: '공식 기관',
            issueUrl: null,
            evidence,
        }));
        await api.login('demo@example.invalid', 'demo');
        mount('/notices/1/check/once');
        await userEvent.click(
            await screen.findByRole('radio', {
                name: '거주 조건을 확인했어요',
            }),
        );
        await userEvent.click(
            screen.getByRole('radio', { name: '주소가 바뀌지 않았어요' }),
        );
        await userEvent.click(
            screen.getByRole('radio', { name: '다른 유형으로 신청해요' }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(
            screen.getByRole('heading', { name: '공통 거주 조건' }),
        ).toBeVisible();
        expect(screen.getByText('답변 완료 1개 · 추가 확인 0개')).toBeVisible();
        expect(screen.queryByText('주소 변경 서류 제출 대상')).toBeNull();
        expect(screen.queryByText('산업단지 추가 조건')).toBeNull();
        expect(
            screen.getByRole('heading', { name: '확인할 서류 1개' }),
        ).toBeVisible();
        expect(
            screen.getByRole('heading', { name: '공통 확인 서류' }),
        ).toBeVisible();
        expect(screen.queryByText('주소 변경 증명서')).toBeNull();
        expect(screen.queryByText('근로자 증빙')).toBeNull();
        await userEvent.click(screen.getByText('공통·맞춤 준비 안내 1개'));
        expect(
            screen.getByRole('heading', { name: '공통 계약 준비 안내' }),
        ).toBeVisible();
        await userEvent.click(
            screen.getByRole('button', { name: '답변 수정' }),
        );
        await userEvent.click(screen.getByRole('button', { name: '다음' }));
        await userEvent.click(
            screen.getByRole('radio', { name: '주소가 바뀌었어요' }),
        );
        await userEvent.click(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(screen.getByText('답변 완료 2개 · 추가 확인 1개')).toBeVisible();
        expect(
            screen.getByRole('heading', { name: '주소 변경 서류 제출 대상' }),
        ).toBeVisible();
        expect(
            screen.getByRole('heading', { name: '산업단지 추가 조건' }),
        ).toBeVisible();
        expect(
            screen.getByRole('heading', { name: '확인할 서류 3개' }),
        ).toBeVisible();
        expect(
            screen.getByRole('heading', { name: '주소 변경 증명서' }),
        ).toBeVisible();
        expect(
            screen.getByRole('heading', { name: '근로자 증빙' }),
        ).toBeVisible();
        expect(await api.preparations()).toEqual([]);
        expect(notices[0].questions).toHaveLength(4);
    });
    it('내 준비 세 탭은 답변에 맞는 항목과 공통 항목을 보이고 답변을 바꾸면 다시 표시한다', async () => {
        const evidence = notices[0].evidence;
        notices[0].questions = [
            {
                id: 'method',
                title: '어떤 방법으로 신청하나요?',
                reason: '온라인 또는 방문 신청을 선택해요.',
                evidence,
                options: [
                    { value: 'ONLINE', label: '인터넷 신청' },
                    { value: 'VISIT', label: '현장 방문 신청' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
            {
                id: 'work',
                title: '산업단지 추가 조건',
                reason: '산업단지 근로자로 신청할 때 확인해요.',
                evidence,
                options: [
                    { value: 'MEETS', label: '기준에 맞아요' },
                    { value: 'NOT_MY_TYPE', label: '다른 유형으로 신청해요' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
        ];
        notices[0].documentTemplates = [
            {
                id: 'common',
                title: '공통 확인 서류',
                method: '공식 서류를 준비해요.',
                issuer: '공식 기관',
                issueUrl: null,
                evidence,
            },
            {
                id: 'visit',
                title: '방문 신청 확인 서류',
                method: '방문할 때 준비해요.',
                issuer: '공식 기관',
                issueUrl: null,
                evidence,
                conditionQuestionId: 'method',
                conditionAnswerValues: ['VISIT'],
            },
        ];
        notices[0].schedules = [
            {
                id: 'online',
                title: '접수 회차 · 온라인 접수 시작',
                date: '2026-12-01',
                evidence,
            },
            {
                id: 'visit',
                title: '접수 회차 · 현장 접수 시작',
                date: '2026-12-02',
                evidence,
            },
            { id: 'common', title: '결과 발표', date: '2026-12-20', evidence },
        ];
        await api.login('demo@example.invalid', 'demo');
        let p = await api.start('1', 1);
        p = await api.answers(p.id, p.revision, {
            method: 'ONLINE',
            work: 'NOT_MY_TYPE',
        });
        let view = mount('/preparations/' + p.id + '/conditions');
        await screen.findByText('내 답변에 맞춘 안내');
        expect(
            screen.queryByText('산업단지 추가 조건'),
        ).not.toBeInTheDocument();
        await userEvent.click(screen.getByRole('link', { name: '서류' }));
        expect(
            await screen.findByRole('button', {
                name: '공통 확인 서류',
            }),
        ).toBeVisible();
        expect(
            screen.queryByRole('button', { name: '방문 신청 확인 서류' }),
        ).not.toBeInTheDocument();
        await userEvent.click(screen.getByRole('link', { name: '일정' }));
        await screen.findByRole('heading', { name: '전체 일정' });
        await userEvent.click(screen.getByLabelText('일정 근거와 안내 펼치기'));
        expect(
            await within(document.getElementById('timeline')!).findByText(
                '접수 회차 · 온라인 접수 시작',
            ),
        ).toBeVisible();
        expect(
            screen.queryByText('접수 회차 · 현장 접수 시작'),
        ).not.toBeInTheDocument();
        expect(
            within(document.getElementById('timeline')!).getByText('결과 발표'),
        ).toBeVisible();
        view.unmount();
        p = await api.answers(p.id, p.revision, {
            method: 'VISIT',
            work: 'UNKNOWN',
        });
        view = mount('/preparations/' + p.id + '/documents');
        expect(
            await screen.findByRole('button', {
                name: '방문 신청 확인 서류',
            }),
        ).toBeVisible();
        await userEvent.click(screen.getByRole('link', { name: '조건' }));
        expect(await screen.findByText('산업단지 추가 조건')).toBeVisible();
        await userEvent.click(screen.getByRole('link', { name: '일정' }));
        await screen.findByRole('heading', { name: '전체 일정' });
        await userEvent.click(screen.getByLabelText('일정 근거와 안내 펼치기'));
        expect(
            await within(document.getElementById('timeline')!).findByText(
                '접수 회차 · 현장 접수 시작',
            ),
        ).toBeVisible();
        expect(
            screen.queryByText('접수 회차 · 온라인 접수 시작'),
        ).not.toBeInTheDocument();
        expect((await api.preparation(p.id)).schedules).toHaveLength(3);
        view.unmount();
    });
    it('새로 수집된 공고의 원본 지역을 목록과 상세에서 같은 약칭으로 표시한다', async () => {
        const notice = {
            ...notices[0],
            id: 'newly-collected-notice',
            title: '[광주광역시] 입주자 모집',
            region: '광주광역시·전라남도',
        };
        const page = { page: 0, size: 20, totalElements: 1, totalPages: 1 };
        const list = vi
            .spyOn(api, 'notices')
            .mockResolvedValueOnce({
                ...page,
                items: [],
                totalElements: 0,
                totalPages: 0,
            })
            .mockResolvedValue({
                ...page,
                items: [demoNoticeListItem(notice)],
            });
        const detail = vi.spyOn(api, 'notice').mockResolvedValue(notice);
        try {
            mount('/notices');
            await screen.findByRole('heading', {
                name: '조건에 맞는 공고가 없어요',
            });
            await userEvent.click(
                screen.getByRole('button', { name: '공고 새로고침' }),
            );
            const title = await screen.findByRole('button', {
                name: notice.title,
            });
            expect(title).toHaveTextContent('[전남광주]');
            expect(screen.getByText('전남광주 · ' + notice.type)).toBeVisible();
            await userEvent.click(title);
            await userEvent.click(
                screen.getByRole('link', { name: '공고 자세히 보기' }),
            );
            const overview = await screen.findByRole('region', {
                name: '[전남광주]',
            });
            expect(within(overview).getByText('전남광주')).toBeVisible();
            await userEvent.click(
                within(overview).getByText('준비 안내 확인 현황'),
            );
            await waitFor(() =>
                expect(overview).toHaveAccessibleName(notice.title),
            );
            expect(within(overview).getByText('전남광주')).toBeVisible();
        } finally {
            list.mockRestore();
            detail.mockRestore();
        }
    });
    it.each(['/?notice=1', '/', '/preparations'])(
        '%s의 공고 카드에는 접수 상태 배지만 표시한다',
        async (path) => {
            if (path !== '/?notice=1') {
                await api.login('demo@example.invalid', 'demo');
                await api.start('1', 1);
            }
            mount(path);
            expect(await screen.findByText('접수 중')).toBeVisible();
            expect(
                screen.queryByRole('button', { name: /^준비 안내/ }),
            ).not.toBeInTheDocument();
        },
    );
    it('관심 공고 카드에서 검수 상태를 펼치고 바깥을 눌러 접는다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const notice = notices.find((item) => item.guideState === 'REVIEWING')!;
        await memberApi.save(notice.id, true);
        mount('/saved');
        const button = await screen.findByRole('button', {
            name: '준비 안내 검수 중',
        });
        expect(button).toHaveAttribute('data-direction', 'left');
        expect(button).toHaveAttribute('data-tone', 'gray');
        expect(button.closest('a')).toBeNull();
        await userEvent.click(button);
        expect(button).toHaveAttribute('aria-expanded', 'true');
        await userEvent.click(
            screen.getByRole('heading', { name: '관심 공고' }),
        );
        expect(button).toHaveAttribute('aria-expanded', 'false');
        expect(
            screen.getByRole('button', { name: '관심 공고 해제' }),
        ).toHaveAttribute('aria-pressed', 'true');
    });
    it('관심 목록의 두 공고를 비교하고 저장된 공고 안에서 선택을 변경한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const [first, second] = notices;
        await memberApi.save(first.id, true);
        await memberApi.save(second.id, true);
        const catalog = vi.spyOn(api, 'notices');
        const position = vi
            .spyOn(window, 'scrollY', 'get')
            .mockReturnValue(147);
        try {
            mount('/saved');
            await userEvent.click(
                await screen.findByRole('button', { name: first.title }),
            );
            await userEvent.click(
                screen.getByRole('link', { name: '공고 비교' }),
            );
            const picker = within(
                await screen.findByRole('region', { name: '2번 공고 검색' }),
            );
            expect(picker.queryByTitle(first.title)).toBeNull();
            expect(picker.getAllByRole('listitem')).toHaveLength(1);
            await userEvent.type(picker.getByRole('textbox'), '없는공고XYZ');
            expect(
                picker.getByText('검색어와 일치하는 관심 공고가 없습니다.'),
            ).toBeVisible();
            await userEvent.clear(picker.getByRole('textbox'));
            await userEvent.click(picker.getByTitle(second.title));
            await screen.findByRole('region', { name: '공식 일정' });
            expect(
                await within(
                    screen.getByRole('region', { name: '2번 비교 공고' }),
                ).findByTitle(second.title),
            ).toBeVisible();
            await userEvent.click(
                screen.getByRole('button', { name: '1번 공고 선택 해제' }),
            );
            const firstPicker = within(
                screen.getByRole('region', { name: '1번 공고 검색' }),
            );
            expect(firstPicker.getAllByRole('listitem')).toHaveLength(1);
            await userEvent.click(firstPicker.getByTitle(first.title));
            expect(catalog).not.toHaveBeenCalled();
            position.mockReturnValue(0);
            await userEvent.click(
                screen.getByRole('link', { name: '이전 화면으로' }),
            );
            expect(
                await screen.findByRole('heading', { name: '관심 공고' }),
            ).toBeVisible();
            expect(
                screen.getByRole('button', { name: first.title }),
            ).toHaveAttribute('aria-expanded', 'true');
            expect(window.scrollTo).toHaveBeenLastCalledWith({
                top: 147,
                behavior: 'instant',
            });
        } finally {
            position.mockRestore();
            catalog.mockRestore();
        }
    });
    it('관심 공고는 하나씩 미리보고 상세에서 같은 카드로 돌아온다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const [first, second] = notices;
        for (const notice of [first, second])
            await memberApi.save(notice.id, true);
        const detail = vi.spyOn(api, 'notice');
        const position = vi
            .spyOn(window, 'scrollY', 'get')
            .mockReturnValue(284);
        try {
            mount('/saved');
            const firstCard = await screen.findByRole('button', {
                name: first.title,
            });
            const secondCard = screen.getByRole('button', {
                name: second.title,
            });
            await userEvent.click(firstCard);
            expect(firstCard).toHaveAttribute('aria-expanded', 'true');
            await userEvent.click(secondCard);
            expect(firstCard).toHaveAttribute('aria-expanded', 'false');
            expect(secondCard).toHaveAttribute('aria-expanded', 'true');
            await userEvent.click(secondCard);
            expect(
                screen.queryByRole('region', { name: '공고 미리보기' }),
            ).toBeNull();
            await userEvent.click(secondCard);
            expect(detail).not.toHaveBeenCalled();
            const link = screen.getByRole('link', { name: '공고 자세히 보기' });
            expect(link).toHaveAttribute('href', '/notices/' + second.id);
            await userEvent.click(link);
            await screen.findByRole('tab', { name: '핵심 요약' });
            await userEvent.click(screen.getByRole('tab', { name: '일정' }));
            position.mockReturnValue(0);
            await userEvent.click(
                screen.getByRole('link', { name: '관심 공고로 돌아가기' }),
            );
            expect(
                await screen.findByRole('button', { name: second.title }),
            ).toHaveAttribute('aria-expanded', 'true');
            expect(window.scrollTo).toHaveBeenLastCalledWith({
                top: 284,
                behavior: 'instant',
            });
        } finally {
            position.mockRestore();
            detail.mockRestore();
        }
    });
    it.each(['/compare', '/saved/compare'])(
        '%s 상세에서 돌아오면 같은 두 공고와 비교 위치를 복원한다',
        async (path) => {
            const [first, second] = notices;
            if (path === '/saved/compare') {
                await api.login('demo@example.invalid', 'demo');
                for (const n of [first, second])
                    await memberApi.save(n.id, true);
            }
            const comparison =
                path + '?' + new URLSearchParams({ a: first.id, b: second.id });
            const y = vi.spyOn(window, 'scrollY', 'get').mockReturnValue(368);
            try {
                mount(comparison);
                const slot = (index: number) =>
                    within(
                        screen.getByRole('region', {
                            name: index + '번 비교 공고',
                        }),
                    );
                await screen.findByRole('region', { name: '2번 비교 공고' });
                await userEvent.click(
                    await slot(2).findByRole('link', {
                        name: '공고 상세 보기',
                    }),
                );
                await userEvent.click(
                    await screen.findByRole('tab', { name: '일정' }),
                );
                y.mockReturnValue(0);
                const back = screen.getByRole('link', {
                    name: '공고 비교로 돌아가기',
                });
                expect(back).toHaveAttribute('href', comparison);
                await userEvent.click(back);
                await screen.findByRole('region', { name: '공식 일정' });
                expect(await slot(1).findByTitle(first.title)).toBeVisible();
                expect(await slot(2).findByTitle(second.title)).toBeVisible();
                expect(window.scrollTo).toHaveBeenLastCalledWith({
                    top: 368,
                    behavior: 'instant',
                });
            } finally {
                y.mockRestore();
            }
        },
    );
    it.each(['이전 화면으로', '현재 공식 근거 보기'])(
        '수정 전후 비교의 %s 링크는 상세 탭과 스크롤 위치를 복원한다',
        async (name) => {
            const notice = notices[0];
            const editions = vi.spyOn(memberApi, 'editions').mockResolvedValue(
                [3, 2, 1].map((version) => ({
                    version,
                    revision: 1,
                    facts: { title: notice.title },
                })),
            );
            const y = vi.spyOn(window, 'scrollY', 'get').mockReturnValue(419);
            try {
                const detailPath = '/notices/' + notice.id + '?tab=schedule';
                mount(detailPath);
                await userEvent.click(
                    await screen.findByText('공고 자료와 비교'),
                );
                await userEvent.click(
                    screen.getByRole('link', { name: '수정 전후 비교' }),
                );
                await userEvent.selectOptions(
                    await screen.findByRole('combobox', {
                        name: '이전 공개본',
                    }),
                    '1.1',
                );
                y.mockReturnValue(0);
                const back = screen.getByRole('link', { name });
                expect(back).toHaveAttribute('href', detailPath);
                await userEvent.click(back);
                expect(
                    await screen.findByRole('tab', {
                        name: '일정',
                        selected: true,
                    }),
                ).toBeVisible();
                expect(
                    screen.getByText('공고 자료와 비교').closest('details'),
                ).toHaveAttribute('open');
                expect(window.scrollTo).toHaveBeenLastCalledWith({
                    top: 419,
                    behavior: 'instant',
                });
            } finally {
                y.mockRestore();
                editions.mockRestore();
            }
        },
    );
    it.each(['공고 확인', '공고 상세로 돌아가기'])(
        '준비 이어하기 후 %s에서 같은 상세 탭과 위치를 복원한다',
        async (name) => {
            await api.login('demo@example.invalid', 'demo');
            await api.start('1', 1);
            const y = vi.spyOn(window, 'scrollY', 'get').mockReturnValue(317);
            try {
                const path = '/notices/1?tab=documents';
                mount(path);
                await userEvent.click(
                    await screen.findByText('공고 자료와 비교'),
                );
                await userEvent.click(
                    await screen.findByRole('button', {
                        name: '준비 이어하기',
                    }),
                );
                if (name === '공고 상세로 돌아가기') {
                    const nav = await screen.findByRole('navigation', {
                        name: '개인 준비',
                    });
                    await userEvent.click(
                        within(nav).getByRole('link', { name: '조건' }),
                    );
                }
                y.mockReturnValue(0);
                const back = await screen.findByRole('link', { name });
                expect(back).toHaveAttribute('href', path);
                await userEvent.click(back);
                expect(
                    await screen.findByRole('tab', {
                        name: '제출서류',
                        selected: true,
                    }),
                ).toBeVisible();
                expect(
                    screen.getByText('공고 자료와 비교').closest('details'),
                ).toHaveAttribute('open');
                expect(window.scrollTo).toHaveBeenLastCalledWith({
                    top: 317,
                    behavior: 'instant',
                });
            } finally {
                y.mockRestore();
            }
        },
    );
    it.each(['직접', '방법 선택'])(
        '이번만 확인에 %s 진입한 뒤 상세 탭과 스크롤을 복원한다',
        async (entry) => {
            const y = vi.spyOn(window, 'scrollY', 'get').mockReturnValue(294);
            try {
                const detailPath = '/notices/1?tab=documents';
                mount(detailPath);
                await screen.findByRole('tab', {
                    name: '제출서류',
                    selected: true,
                });
                if (entry === '직접') {
                    await userEvent.click(screen.getByText('공고 자료와 비교'));
                    await userEvent.click(
                        screen.getByRole('link', {
                            name: '저장하지 않고 이번만 확인',
                        }),
                    );
                } else {
                    await userEvent.click(
                        screen.getByRole('button', {
                            name: '내 조건 확인하기',
                        }),
                    );
                    await userEvent.click(
                        await screen.findByRole('button', {
                            name: '저장하지 않고 이번만 확인',
                        }),
                    );
                }
                await userEvent.click(
                    await screen.findByRole('radio', { name: '확인했어요' }),
                );
                if (entry === '직접') {
                    await userEvent.click(
                        screen.getByRole('radio', { name: '해당하지 않아요' }),
                    );
                    await screen.findByRole('heading', {
                        name: '이번 조건 확인 결과',
                    });
                }
                y.mockReturnValue(0);
                const back = screen.getByRole('link', {
                    name: '이전 화면으로',
                });
                expect(back).toHaveAttribute('href', detailPath);
                await userEvent.click(back);
                expect(
                    await screen.findByRole('tab', {
                        name: '제출서류',
                        selected: true,
                    }),
                ).toBeVisible();
                expect(
                    screen.getByText('공고 자료와 비교').closest('details')
                        ?.open,
                ).toBe(entry === '직접');
                expect(window.scrollTo).toHaveBeenLastCalledWith({
                    top: 294,
                    behavior: 'instant',
                });
            } finally {
                y.mockRestore();
            }
        },
    );
    it('상세에서 비교 대상을 변경해도 복귀 시 원래 공고의 탭과 자료 위치를 복원한다', async () => {
        const [original, other] = notices;
        const y = vi.spyOn(window, 'scrollY', 'get').mockReturnValue(384);
        try {
            mount('/notices/' + original.id);
            await userEvent.click(
                await screen.findByRole('tab', { name: '제출서류' }),
            );
            await userEvent.click(screen.getByText('공고 자료와 비교'));
            await userEvent.click(
                screen.getByRole('link', { name: '공고 비교' }),
            );
            await userEvent.click(
                screen.getByRole('button', { name: '1번 공고 변경' }),
            );
            const search = within(
                screen.getByRole('region', { name: '1번 공고 검색' }),
            );
            await userEvent.click(await search.findByTitle(other.title));
            y.mockReturnValue(0);
            const back = screen.getByRole('link', { name: '이전 화면으로' });
            expect(back).toHaveAttribute(
                'href',
                '/notices/' + original.id + '?tab=documents',
            );
            await userEvent.click(back);
            expect(
                await screen.findByRole('tab', { name: '제출서류' }),
            ).toHaveAttribute('aria-selected', 'true');
            expect(
                screen.getByText('공고 자료와 비교').closest('details'),
            ).toHaveAttribute('open');
            expect(window.scrollTo).toHaveBeenLastCalledWith({
                top: 384,
                behavior: 'instant',
            });
        } finally {
            y.mockRestore();
        }
    });
    it('공고 비교에서 선택을 바꿔도 돌아오면 원래 목록과 미리보기 위치를 복원한다', async () => {
        const position = vi
            .spyOn(window, 'scrollY', 'get')
            .mockReturnValue(513);
        try {
            mount('/notices?sort=deadline&page=0');
            const title = notices[0].title;
            await userEvent.click(
                await screen.findByRole('button', { name: title }),
            );
            await userEvent.click(
                screen.getByRole('link', { name: '공고 비교' }),
            );
            const picker = () =>
                within(screen.getByRole('region', { name: '1번 공고 검색' }));
            await userEvent.click(await picker().findByTitle(notices[1].title));
            await userEvent.click(
                screen.getByRole('button', { name: '1번 공고 선택 해제' }),
            );
            position.mockReturnValue(0);
            const back = screen.getByRole('link', { name: '이전 화면으로' });
            expect(back).toHaveAttribute(
                'href',
                '/notices?sort=deadline&page=0',
            );
            await userEvent.click(back);
            expect(
                await screen.findByRole('button', { name: title }),
            ).toHaveAttribute('aria-expanded', 'true');
            expect(window.scrollTo).toHaveBeenLastCalledWith({
                top: 513,
                behavior: 'instant',
            });
        } finally {
            position.mockRestore();
        }
    });
    it('공고 미리보기는 목록 데이터로 열고 상세 화면에 들어갈 때만 전체 자료를 요청한다', async () => {
        const detail = vi.spyOn(api, 'notice');
        const position = vi
            .spyOn(window, 'scrollY', 'get')
            .mockReturnValue(419);
        try {
            mount('/notices');
            await userEvent.click(
                await screen.findByRole('button', { name: notices[0].title }),
            );
            const link = await screen.findByRole('link', {
                name: '공고 자세히 보기',
            });
            expect(detail).not.toHaveBeenCalled();
            await userEvent.click(link);
            await screen.findByRole('tab', { name: '핵심 요약' });
            expect(detail).toHaveBeenCalledTimes(1);
            await userEvent.click(screen.getByRole('tab', { name: '조건' }));
            position.mockReturnValue(0);
            await userEvent.click(
                screen.getByRole('link', { name: '공고 목록 보기' }),
            );
            expect(
                await screen.findByRole('button', { name: notices[0].title }),
            ).toHaveAttribute('aria-expanded', 'true');
            expect(window.scrollTo).toHaveBeenLastCalledWith({
                top: 419,
                behavior: 'instant',
            });
        } finally {
            position.mockRestore();
            detail.mockRestore();
        }
    });
    it('내 준비의 세부 탭은 선택한 준비 건을 공유하고 같은 상세를 반복 요청하지 않는다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        const detail = vi.spyOn(api, 'preparation');
        try {
            mount('/preparations');
            await screen.findByRole('heading', { name: p.title });
            for (const name of ['조건', '서류', '일정', '개요']) {
                const nav = screen.getByRole('navigation', {
                    name: '개인 준비',
                });
                await userEvent.click(within(nav).getByRole('link', { name }));
                await waitFor(() =>
                    expect(
                        within(
                            screen.getByRole('navigation', {
                                name: '개인 준비',
                            }),
                        ).getByRole('link', { name }),
                    ).toHaveAttribute('aria-current', 'page'),
                );
            }
            expect(detail.mock.calls.map(([id]) => id)).toEqual([p.id]);
        } finally {
            detail.mockRestore();
        }
    });

    it.each(['/notices', '/notices?sort='])(
        '%s 기본 정렬은 접수·준비 안내 순이고 명시적 마감순으로 전환할 수 있다',
        async (path) => {
            mount(path);
            const results = screen.getByRole('region', {
                name: '공고 검색 결과',
            });
            await within(results).findByRole('button', {
                name: notices[0].title,
            });
            const titles = () =>
                within(results)
                    .getAllByRole('heading', { level: 3 })
                    .map((heading) => heading.textContent);
            expect(titles()).toEqual([
                notices[0].title,
                notices[1].title,
                notices[3].title,
                notices[2].title,
            ]);
            await userEvent.click(screen.getByLabelText('공고 정렬'));
            expect(
                screen.getByRole('button', { name: /접수·준비 안내 순/ }),
            ).toHaveAttribute('aria-pressed', 'true');
            await userEvent.click(
                screen.getByRole('button', { name: /마감 가까운 순/ }),
            );
            await waitFor(() => expect(titles()[0]).toBe(notices[2].title));
            await userEvent.click(screen.getByLabelText('공고 정렬'));
            expect(
                screen.getByRole('button', { name: /마감 가까운 순/ }),
            ).toHaveAttribute('aria-pressed', 'true');
            await userEvent.click(
                screen.getByRole('button', { name: /접수·준비 안내 순/ }),
            );
            await waitFor(() => expect(titles().at(-1)).toBe(notices[2].title));
        },
    );
    it('읽지 않은 알림을 종에 표시하고 모두 읽은 후 표시를 지운다', async () => {
        vi.useFakeTimers({ toFake: ['Date'] });
        try {
            vi.setSystemTime(new Date(notices[0].endDate + 'T00:00:00+09:00'));
            await api.login('demo@example.invalid', 'demo');
            await memberApi.configure({
                enabled: true,
                leadDays: 3,
                includeSaved: true,
            });
            await memberApi.save(notices[0].id, true);
            mount('/notices');
            const link = await screen.findByRole('link', {
                name: '알림 확인, 읽지 않은 알림 있음',
            });
            expect(link).toHaveAttribute('data-unread', 'true');
            await userEvent.click(link);
            await userEvent.click(
                await screen.findByRole('button', { name: '모두 읽음' }),
            );
            await screen.findByRole('heading', {
                name: '공고 소식 · 읽지 않음 0건',
            });
            await userEvent.click(
                screen.getByRole('button', { name: '이전 화면으로' }),
            );
            expect(
                await screen.findByRole('link', { name: '알림 확인' }),
            ).not.toHaveAttribute('data-unread');
        } finally {
            vi.useRealTimers();
        }
    });
    it('알림을 열면 연결된 공고로 이동하고 해당 알림만 읽음 처리한다', async () => {
        vi.useFakeTimers({ toFake: ['Date'] });
        try {
            vi.setSystemTime(new Date(notices[0].endDate + 'T00:00:00+09:00'));
            await api.login('demo@example.invalid', 'demo');
            await memberApi.configure({
                enabled: true,
                leadDays: 3,
                includeSaved: true,
            });
            const targets = notices.slice(0, 2);
            for (const notice of targets) await memberApi.save(notice.id, true);
            mount('/alerts');
            await screen.findByRole('heading', {
                name: '공고 소식 · 읽지 않음 2건',
            });

            for (const [index, notice] of targets.entries()) {
                const row = (await screen.findByText(notice.title)).closest(
                    'li',
                )!;
                expect(
                    within(row).getByRole('img', { name: '읽지 않음' }),
                ).toBeInTheDocument();
                expect(within(row).getByRole('link')).toHaveAttribute(
                    'href',
                    '/notices/' + notice.id,
                );
                await userEvent.click(within(row).getByText(/신청 접수 마감/));
                await screen.findByRole('tab', { name: '핵심 요약' });
                const bell = await screen.findByRole('link', {
                    name:
                        index === 0
                            ? '알림 확인, 읽지 않은 알림 있음'
                            : '알림 확인',
                });
                await userEvent.click(bell);
                await screen.findByRole('heading', {
                    name: `공고 소식 · 읽지 않음 ${1 - index}건`,
                });
                expect(
                    screen.queryAllByRole('img', { name: '읽지 않음' }),
                ).toHaveLength(1 - index);
                const list = screen.getByText(notice.title).closest('ul')!;
                const expectedOrder =
                    index === 0 ? [targets[1], targets[0]] : targets;
                expect(
                    within(list)
                        .getAllByRole('link')
                        .map((link) => link.getAttribute('href')),
                ).toEqual(
                    expectedOrder.map((target) => '/notices/' + target.id),
                );
                expect(
                    within(
                        screen.getByText(notice.title).closest('li')!,
                    ).queryByText('읽음'),
                ).not.toBeInTheDocument();
            }
        } finally {
            vi.useRealTimers();
        }
    });
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
        const p = await api.start('1', 1);
        await api.answers(p.id, p.revision, { [p.questions[0].id]: 'YES' });
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
        expect(
            screen.getByRole('heading', {
                name: '본인에게 적용되는 무주택 기준',
            }),
        ).toBeVisible();
        await userEvent.click(screen.getByText('답변 완료 1개 보기'));
        const residence = screen.getByRole('heading', {
            name: '공고 기준일의 거주 조건',
        });
        expect(residence).toBeVisible();
        expect(
            within(residence.parentElement!).getByText(
                '실제 공고의 기준일과 주민등록상 거주지를 확인해야 해요',
            ),
        ).toBeVisible();
        expect(screen.getByText('확인했어요')).toBeVisible();
        await userEvent.click(source);
        expect(detail).toHaveAttribute('open');
        expect(screen.queryByText('답변 이어서 작성')).not.toBeInTheDocument();
        const resume = screen.getByRole('link', { name: '준비 이어하기' });
        expect(resume.closest('[role="region"]')).toBeNull();
        await userEvent.click(resume);
        expect(
            await screen.findByRole('group', {
                name: '본인에게 적용되는 무주택 기준을 확인했나요?',
            }),
        ).toBeVisible();
    });
    it.each([false, true])(
        '내 준비 조건의 쉬운 제목과 펼친 설명 (답변 완료 %s)',
        async (answered) => {
            await api.login('demo@example.invalid', 'demo');
            const p = await api.start('1', 1);
            const question = {
                ...p.questions[0],
                title: '세대 전체의 자동차 기준을 확인했나요?',
                reason: '자동차 기준은 완화하지 않습니다. 비영업용 승용자동차별 가액 중 최고 가액이 5,451만원 이하인지 확인합니다. 배우자 등 다른 세대원의 차량도 포함합니다.',
            };
            const read = vi.spyOn(api, 'preparation').mockResolvedValue({
                ...p,
                questions: [question],
                answers: answered ? { [question.id]: 'YES' } : {},
            });
            try {
                mount('/preparations/' + p.id + '/conditions');
                if (answered)
                    await userEvent.click(
                        await screen.findByText('답변 완료 1개 보기'),
                    );
                expect(
                    await screen.findByRole('heading', {
                        name: '우리 집 자동차 기준은 얼마인가요?',
                    }),
                ).toBeVisible();
                expect(
                    screen.getByText(
                        '영업용이 아닌 승용차 중 가장 비싼 차가 5,451만 원 이하',
                    ),
                ).toBeVisible();
                const detail = screen.getByText(
                    /배우자 등 다른 세대원의 차도 함께 봐요/,
                );
                expect(detail).not.toBeVisible();
                await userEvent.click(
                    screen.getByLabelText(/자동차 기준.*공식 근거 보기/),
                );
                expect(detail).toBeVisible();
            } finally {
                read.mockRestore();
            }
        },
    );
    it('결과 카드에서 선택한 추가 확인 질문의 선택지를 바로 보여준다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const preparation = await api.start('1', 1);
        mount('/preparations/' + preparation.id + '/conditions/result');
        const card = (
            await screen.findByRole('heading', {
                name: '본인에게 적용되는 무주택 기준',
            })
        ).closest('article');
        expect(card).not.toBeNull();
        await userEvent.click(
            within(card!).getByRole('link', { name: '답변 이어서 작성' }),
        );
        expect(
            await screen.findByRole('group', {
                name: '본인에게 적용되는 무주택 기준을 확인했나요?',
            }),
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
            await screen.findByRole('link', { name: '준비 이어하기' }),
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
    it.each(['YES', 'UNKNOWN'])(
        '지금 할 일에서 미응답 질문을 열고 기존 %s 응답의 확인 상태에 따라 이전 질문을 표시한다',
        async (answer) => {
            await api.login('demo@example.invalid', 'demo');
            const p = await api.start('1', 1);
            await api.answers(p.id, p.revision, {
                [p.questions[0].id]: answer,
            });
            mount('/preparations');
            await userEvent.click(
                await screen.findByRole('link', { name: /^지금 할 일/ }),
            );
            expect(
                await screen.findByRole('group', {
                    name: '본인에게 적용되는 무주택 기준을 확인했나요?',
                }),
            ).toBeVisible();
            const previous = screen.getByRole('button', { name: '이전' });
            if (answer === 'UNKNOWN') {
                await userEvent.click(previous);
                expect(
                    screen.getByRole('group', {
                        name: '공고 기준일의 거주 조건을 확인했나요?',
                    }),
                ).toHaveAccessibleName('공고 기준일의 거주 조건을 확인했나요?');
                expect(
                    screen.getByRole('radio', { name: '잘 모르겠어요' }),
                ).toBeChecked();
            } else {
                expect(previous).toBeDisabled();
                await userEvent.click(
                    screen.getByRole('radio', { name: '확인했어요' }),
                );
                expect(
                    await screen.findByRole('heading', {
                        name: '내 조건 확인 결과',
                    }),
                ).toBeVisible();
            }
        },
    );
    it('모두 응답한 뒤에는 추가 확인으로 남긴 질문을 이어간다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        await api.answers(p.id, p.revision, {
            [p.questions[0].id]: 'YES',
            [p.questions[1].id]: 'UNKNOWN',
        });
        mount('/preparations');
        await userEvent.click(
            await screen.findByRole('link', { name: /^지금 할 일/ }),
        );
        expect(
            await screen.findByRole('group', {
                name: '본인에게 적용되는 무주택 기준을 확인했나요?',
            }),
        ).toBeVisible();
        expect(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        ).toBeChecked();
    });
    it.each(['저장', '이번만'] as const)(
        '%s 확인에서 단순 안내는 건너뛰고 개인 상황의 답변을 필요한 서류로 연결한다',
        async (mode) => {
            notices[0].questions[0] = {
                ...notices[0].questions[0],
                title: '재계약 안내를 확인했나요?',
                options: [
                    { value: 'ACK', label: '안내를 확인했어요' },
                    {
                        value: 'UNKNOWN',
                        label: '잘 모르겠어요',
                        requiresFollowUp: true,
                    },
                ],
            };
            notices[0].questions[1] = {
                ...notices[0].questions[1],
                title: '어떤 방법으로 신청하나요?',
                reason: '현장에서 직접 신청하면 조건별 확인 서류를 준비해요.',
                options: [
                    { value: 'YES', label: '현장에서 직접 신청해요' },
                    { value: 'NO', label: '인터넷으로 신청해요' },
                    {
                        value: 'UNKNOWN',
                        label: '잘 모르겠어요',
                        requiresFollowUp: true,
                    },
                ],
            };
            notices[0].documentTemplates = [
                {
                    id: '1',
                    title: '조건별 확인 서류',
                    conditionQuestionId: 'house',
                    conditionAnswerValues: ['YES'],
                    method: '공고에서 발급 기준을 확인하세요.',
                    issuer: '공고에서 확인',
                    issueUrl: null,
                    evidence: notices[0].evidence,
                },
            ];
            await api.login('demo@example.invalid', 'demo');
            let preparationId: string | undefined;
            if (mode === '저장') {
                const p = await api.start('1', 1);
                preparationId = p.id;
                mount(`/preparations/${p.id}/conditions/edit`);
            } else {
                mount('/notices/1/check/once');
            }
            await screen.findByRole('group', {
                name: '어떤 방법으로 신청하나요?',
            });
            expect(
                screen.queryByRole('radio', { name: '안내를 확인했어요' }),
            ).toBeNull();
            expect(
                screen.getByLabelText('전체 1개 중 1번째 질문'),
            ).toBeVisible();
            await userEvent.click(
                screen.getByRole('radio', { name: '현장에서 직접 신청해요' }),
            );
            await screen.findByRole('heading', {
                name:
                    mode === '저장'
                        ? '내 조건 확인 결과'
                        : '이번 조건 확인 결과',
            });
            const guide = within(
                screen.getByRole('region', {
                    name: '내 답변에 맞춘 준비 안내',
                }),
            );
            expect(
                guide.getByRole('heading', { name: '신청 방법' }),
            ).toBeVisible();
            expect(guide.getByText('현장에서 직접 신청해요')).toBeVisible();
            expect(
                guide.getByText('준비할 서류: 조건별 확인 서류'),
            ).toBeVisible();
            if (preparationId) {
                const reference = guide
                    .getByRole('heading', { level: 4 })
                    .closest('li')!;
                const scroll = vi.spyOn(scrolling, 'animateScroll');
                const bounds = vi
                    .spyOn(reference, 'getBoundingClientRect')
                    .mockReturnValue(new DOMRect(0, 240, 320, 120));
                try {
                    await userEvent.click(
                        within(reference).getByRole('heading'),
                    );
                    expect(reference.querySelector('details')).toHaveAttribute(
                        'open',
                    );
                    await waitFor(() => expect(scroll).toHaveBeenCalled());
                    const [target, position] = scroll.mock.lastCall!;
                    expect(target).toBe(window);
                    expect(
                        typeof position === 'function' ? position() : position,
                    ).toBe(228);
                } finally {
                    scroll.mockRestore();
                    bounds.mockRestore();
                }
                const saved = await api.preparation(preparationId);
                expect(saved.answers).toEqual({ house: 'YES' });
                expect(
                    saved.documents.find((document) => document.id === '1')
                        ?.necessity,
                ).toBe('CONDITIONAL');
                expect(saved.warnings).toEqual([]);
                await userEvent.click(
                    screen.getByRole('link', { name: '내 준비 목록 보기' }),
                );
                expect(
                    await screen.findByRole('button', {
                        name: /조건별 확인 서류/,
                    }),
                ).toBeVisible();
            } else {
                expect(
                    screen.getByRole('heading', { name: '확인할 서류 1개' }),
                ).toBeVisible();
                expect(
                    screen.getByRole('heading', { name: '조건별 확인 서류' }),
                ).toBeVisible();
                expect(await api.preparations()).toEqual([]);
            }
        },
    );
    it('질문별 선택지를 자동 저장하고 다음 질문과 결과로 이동한다', async () => {
        notices[0].questions[0].title =
            '신청자의 성년 또는 미성년 예외를 확인했나요?';
        notices[0].questions[0].reason =
            '원칙은 만19세 이상입니다. 미성년 예외는 같은 등본의 자녀가 있는 세대주, 직계존속 사망·실종 등으로 같은 등본의 형제자매를 부양하는 세대주, 외국인 부모와 내국인 미성년 세대주인 한부모가족입니다.';
        notices[0].questions[0].options = [
            { value: 'ADULT', label: '만 19세 이상이에요' },
            { value: 'EXCEPTION', label: '미성년 예외에 해당해요' },
            { value: 'UNKNOWN', label: '잘 모르겠어요' },
        ];
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/preparations/' + p.id + '/conditions/edit');
        const first = within(
            await screen.findByRole('group', {
                name: '신청자의 성년 또는 미성년 예외를 확인했나요?',
            }),
        );
        expect(
            first.getByText('만 19세부터 신청 · 미성년자는 일부 예외'),
        ).toBeVisible();
        const exception = /같은 등본의 형제자매를 돌봐요/;
        expect(first.getByText(exception)).not.toBeVisible();
        await userEvent.click(
            first
                .getByText('만 19세부터 신청 · 미성년자는 일부 예외')
                .closest('section')!,
        );
        expect(first.getByText(exception)).toBeVisible();
        await userEvent.click(first.getByText(exception));
        expect(first.getByText(exception)).not.toBeVisible();
        await userEvent.click(first.getByText('답변을 고를 때 참고해요'));
        expect(first.getByText(exception)).toBeVisible();
        await userEvent.click(
            await screen.findByRole('radio', {
                name: '미성년 예외에 해당해요',
            }),
        );
        const second = within(
            await screen.findByRole('group', {
                name: '본인에게 적용되는 무주택 기준을 확인했나요?',
            }),
        );
        expect(
            second.getByText('가구원 범위와 예외는 공고마다 달라요'),
        ).toBeVisible();
        expect(second.queryByText(exception)).not.toBeInTheDocument();
        await userEvent.click(
            await screen.findByRole('radio', { name: '해당하지 않아요' }),
        );
        await screen.findByRole('heading', { name: '내 조건 확인 결과' });
        await userEvent.click(screen.getByText('답변 완료 2개 보기'));
        expect(screen.getByText('미성년 예외에 해당해요')).toBeVisible();
        expect((await api.preparation(p.id)).answers.residence).toBe(
            'EXCEPTION',
        );
    });
    it('서로 다른 답변은 자동 진행을 멈추고 저장 후에도 필요 서류와 재확인 경로를 유지한다', async () => {
        const message =
            '앞에서 선택한 신청 유형에는 이 서류가 필요해요. 두 답변을 다시 확인해 주세요.';
        notices[0].questions[1].answerDependencies = [
            {
                questionId: 'residence',
                answerValues: ['YES'],
                conflictingValues: ['NO'],
                requiredDocumentIds: ['1'],
                message,
            },
        ];
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/preparations/' + p.id + '/conditions/edit');
        await userEvent.click(
            await screen.findByRole('radio', { name: '확인했어요' }),
        );
        await screen.findByRole('group', {
            name: '본인에게 적용되는 무주택 기준을 확인했나요?',
        });
        await userEvent.click(
            screen.getByRole('radio', { name: '해당하지 않아요' }),
        );
        expect(screen.getByText(message)).toBeVisible();
        expect((await api.preparation(p.id)).answers.house).toBeUndefined();
        await userEvent.click(screen.getByRole('button', { name: '다음' }));
        await screen.findByRole('heading', { name: '내 조건 확인 결과' });
        const conflicting = await api.preparation(p.id);
        expect(conflicting.answers.house).toBe('NO');
        expect(
            conflicting.documents.find((document) => document.id === '1')
                ?.necessity,
        ).toBe('CONDITIONAL');
        expect(conflicting.warnings).toContain(message);
        expect(screen.getByText(message)).toBeVisible();
        await userEvent.click(
            screen.getByRole('link', { name: '답변 이어서 작성' }),
        );
        await userEvent.click(
            await screen.findByRole('radio', { name: '확인했어요' }),
        );
        await screen.findByRole('heading', { name: '내 조건 확인 결과' });
        expect(screen.queryByText(message)).toBeNull();
        const fixed = await api.preparation(p.id);
        expect(fixed.answers.house).toBe('YES');
        expect(
            fixed.documents.find((document) => document.id === '1')?.necessity,
        ).toBe('CONDITIONAL');
        expect(fixed.warnings).not.toContain(message);
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
            expect(
                screen.getByRole('group', {
                    name: '공고 기준일의 거주 조건을 확인했나요?',
                }),
            ).toHaveAccessibleName('공고 기준일의 거주 조건을 확인했나요?');
            expect(
                screen.getByRole('radio', { name: '잘 모르겠어요' }),
            ).toBeChecked();
            await userEvent.click(screen.getByRole('button', { name: '다음' }));
            await waitFor(() =>
                expect(
                    screen.getByRole('group', {
                        name: '본인에게 적용되는 무주택 기준을 확인했나요?',
                    }),
                ).toHaveAccessibleName(
                    '본인에게 적용되는 무주택 기준을 확인했나요?',
                ),
            );
            expect(write).toHaveBeenCalledTimes(2);
            expect(
                (await api.preparation(p.id)).answers[p.questions[0].id],
            ).toBe('UNKNOWN');
            await userEvent.click(screen.getByRole('button', { name: '이전' }));
            expect(
                screen.getByRole('group', {
                    name: '공고 기준일의 거주 조건을 확인했나요?',
                }),
            ).toHaveAccessibleName('공고 기준일의 거주 조건을 확인했나요?');
            await userEvent.click(
                screen.getByRole('radio', { name: '잘 모르겠어요' }),
            );
            await waitFor(() =>
                expect(
                    screen.getByRole('group', {
                        name: '본인에게 적용되는 무주택 기준을 확인했나요?',
                    }),
                ).toHaveAccessibleName(
                    '본인에게 적용되는 무주택 기준을 확인했나요?',
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
        const disclosure = screen
            .getByText('조건별 확인 서류')
            .closest('summary')!;
        expect(disclosure.closest('details')).not.toHaveAttribute('open');
        await userEvent.click(disclosure);
        expect(
            screen.getByText(/해당 여부 추가 확인 · .*무주택 기준/),
        ).toBeVisible();
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
        await userEvent.click(
            await screen.findByRole('button', { name: /거주 확인 서류/ }),
        );
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
            screen.getByText(/표시할 조건 원문은 아직 확인 중이에요/),
        ).toBeInTheDocument();
        expect(
            within(screen.getByRole('tabpanel', { name: '조건' })).getByText(
                '공식 링크 확인 필요',
            ),
        ).toBeInTheDocument();
        expect(screen.queryByRole('radio')).not.toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: '내 조건 확인하기' }),
        ).not.toBeInTheDocument();
        for (const question of notices.find((notice) => notice.id === '2')!
            .questions)
            expect(screen.queryByText(question.title)).not.toBeInTheDocument();
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
            await userEvent.click(
                await screen.findByRole('button', {
                    name: '저장하고 이어하기',
                }),
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
            await userEvent.click(
                await screen.findByRole('button', {
                    name: '저장하고 이어하기',
                }),
            );
            expect(
                await screen.findByRole('link', { name: '준비 이어하기' }),
            ).toBeVisible();
            const records = await api.preparations();
            expect(records.map((p) => p.noticeId)).toEqual(['1']);
            expect(
                screen.getByRole('heading', { name: records[0].title }),
            ).toBeVisible();
            expect(
                await screen.findByRole('link', { name: /가상 운영기관/ }),
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
            await userEvent.click(
                await screen.findByRole('button', {
                    name: first.documents[0].title,
                }),
            );
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
                label: '서류 다시 확인하기',
                reason: '조건 변경으로 재확인이 필요한 서류가 있어요.',
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
                label: '신청 일정 확인하기',
                reason: '공식 절차와 일정을 확인하세요.',
                path: '/preparations/another-preparation/schedule',
            },
        };
        const read = vi.spyOn(api, 'preparations').mockResolvedValue(
            [first, second].map(
                ({ id, noticeId, title, version, revision, progress }) => ({
                    id,
                    noticeId,
                    title,
                    version,
                    revision,
                    progress,
                }),
            ),
        );
        const detail = vi
            .spyOn(api, 'preparation')
            .mockImplementation(async (id) =>
                id === first.id ? first : second,
            );
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
                    screen.getByRole('link', { name: /^지금 할 일/ }),
                ).toHaveAttribute('href', record.nextAction.path);
                expect(
                    screen.getByRole('link', { name: '이전 준비 기록 보기' }),
                ).toHaveAttribute('href', `/preparations/${record.id}/history`);
            }
            await expectSelected(first);
            expect(detail.mock.calls.map(([id]) => id)).toEqual([first.id]);
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
            expect(detail.mock.calls.map(([id]) => id)).toEqual([
                first.id,
                second.id,
            ]);
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
            expect(detail).toHaveBeenCalledTimes(2);
        } finally {
            read.mockRestore();
            detail.mockRestore();
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
        const read = vi.spyOn(api, 'preparations').mockResolvedValue(
            [first, second].map(
                ({ id, noticeId, title, version, revision, progress }) => ({
                    id,
                    noticeId,
                    title,
                    version,
                    revision,
                    progress,
                }),
            ),
        );
        const detail = vi
            .spyOn(api, 'preparation')
            .mockImplementation(async (id) =>
                id === first.id ? first : second,
            );
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
            detail.mockRestore();
            localStorage.clear();
        }
    });
    it('로그인 복귀부터 모름 응답 저장과 서류 실패 후 재시도까지', async () => {
        mount('/notices/1');
        await userEvent.click(
            await screen.findByRole('button', { name: '내 조건 확인하기' }),
        );
        await userEvent.click(
            await screen.findByRole('button', { name: '저장하고 이어하기' }),
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
            expect(
                screen.getByRole('group', {
                    name: '본인에게 적용되는 무주택 기준을 확인했나요?',
                }),
            ).toHaveAccessibleName(
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
        await userEvent.click(
            await screen.findByRole('button', { name: /거주 확인 서류/ }),
        );
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
            mount('/notices?region=서울+외&region=서울&sort=newest&page=2');
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
    it('서류 카드 본문으로 펼치고 접을 수 있다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const p = await api.start('1', 1);
        mount('/preparations/' + p.id + '/documents');
        const title = p.documents[0].title;
        const toggle = await screen.findByRole('button', {
            name: new RegExp(title),
        });
        expect(toggle).toHaveAttribute('aria-expanded', 'false');
        expect(toggle).toHaveAccessibleName(title);
        await userEvent.click(screen.getByText(title, { selector: 'strong' }));
        expect(toggle).toHaveAttribute('aria-expanded', 'true');
        await userEvent.click(screen.getByText(title, { selector: 'strong' }));
        expect(toggle).toHaveAttribute('aria-expanded', 'false');
    });
    it('준비 완료와 변경사항 저장이 성공하면 서류 카드와 편집 영역이 함께 접힌다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const started = await api.start('1', 1);
        const p = await api.answers(started.id, started.revision, {
            house: 'YES',
        });
        mount('/preparations/' + p.id + '/documents');
        const cardToggle = await screen.findByRole('button', {
            name: p.documents[0].title,
        });
        await userEvent.click(cardToggle);
        const ready = screen.getByRole('button', { name: '준비 완료' });
        const options = within(cardToggle.parentElement!)
            .getByText('상태·메모 수정')
            .closest('details')!;
        expect(options).not.toHaveAttribute('open');
        expect(
            ready.compareDocumentPosition(options) &
                Node.DOCUMENT_POSITION_FOLLOWING,
        ).toBeTruthy();
        await userEvent.click(ready);
        expect(
            await screen.findByText('이 서류의 준비 기록을 저장했어요.'),
        ).toBeInTheDocument();
        expect(cardToggle).toHaveAttribute('aria-expanded', 'false');
        expect(cardToggle).toHaveAccessibleName(p.documents[0].title);
        expect(cardToggle).toHaveAccessibleDescription('준비 완료');
        const nextDocument = document.querySelector('[data-active] > button');
        expect(nextDocument).not.toBe(cardToggle);
        expect(nextDocument).toHaveAttribute('aria-expanded', 'true');
        expect(
            document
                .querySelectorAll('[data-active] > button')
                .item(
                    document.querySelectorAll('[data-active] > button').length -
                        1,
                ),
        ).toBe(cardToggle);
        await userEvent.click(cardToggle);
        expect(
            screen.getByRole('button', { name: '준비 완료 취소' }),
        ).toBeEnabled();
        expect((await api.preparation(p.id)).documents[0].state).toBe('READY');
        expect(options).not.toHaveAttribute('open');
        await userEvent.click(
            within(cardToggle.parentElement!).getByText('상태·메모 수정'),
        );
        await userEvent.selectOptions(
            screen.getByRole('combobox', { name: '준비 상태' }),
            'NEEDED',
        );
        await userEvent.type(within(options).getByRole('textbox'), '발급 예정');
        vi.spyOn(api, 'document').mockRejectedValueOnce(new Error('저장 실패'));
        await userEvent.click(
            within(options).getByRole('button', { name: '변경사항 저장' }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent('저장 실패');
        expect(cardToggle).toHaveAttribute('aria-expanded', 'true');
        expect(options.open).toBe(true);
        expect(within(options).getByRole('textbox')).toHaveValue('발급 예정');
        expect(within(options).getByRole('combobox')).toHaveValue('NEEDED');
        await userEvent.click(
            screen.getByRole('button', { name: '변경사항 저장' }),
        );
        await waitFor(() => expect(options.open).toBe(false));
        expect(cardToggle).toHaveAttribute('aria-expanded', 'false');
        expect(cardToggle).toHaveAccessibleName(p.documents[0].title);
        expect((await api.preparation(p.id)).documents[0].note).toBe(
            '발급 예정',
        );
        await waitFor(async () =>
            expect((await api.preparation(p.id)).documents[0].state).toBe(
                'NEEDED',
            ),
        );
        await userEvent.click(cardToggle);
        expect(screen.getByRole('button', { name: '준비 완료' })).toBeEnabled();
        await userEvent.click(within(options).getByText('상태·메모 수정'));
        await userEvent.type(
            within(options).getByRole('textbox'),
            ' · 발급 완료',
        );
        vi.mocked(api.document).mockRejectedValueOnce(new Error('저장 실패'));
        await userEvent.click(ready);
        expect(await screen.findByRole('alert')).toHaveTextContent('저장 실패');
        expect(cardToggle).toHaveAttribute('aria-expanded', 'true');
        expect(options.open).toBe(true);
        expect(within(options).getByRole('textbox')).toHaveValue(
            '발급 예정 · 발급 완료',
        );
        await userEvent.click(ready);
        await waitFor(() => expect(options.open).toBe(false));
        expect(cardToggle).toHaveAttribute('aria-expanded', 'false');
        expect((await api.preparation(p.id)).documents[0]).toMatchObject({
            state: 'READY',
            note: '발급 예정 · 발급 완료',
        });
        await userEvent.click(cardToggle);
        const undo = screen.getByRole('button', { name: '준비 완료 취소' });
        vi.mocked(api.document).mockRejectedValueOnce(
            new Error('취소 저장 실패'),
        );
        await userEvent.click(undo);
        expect(await screen.findByRole('alert')).toHaveTextContent(
            '취소 저장 실패',
        );
        expect(cardToggle).toHaveAttribute('aria-expanded', 'true');
        expect(cardToggle).toHaveTextContent('준비 완료');
        expect(options.open).toBe(false);
        await userEvent.click(undo);
        await waitFor(() =>
            expect(cardToggle).toHaveAttribute('aria-expanded', 'false'),
        );
        expect(cardToggle).toHaveAccessibleName(p.documents[0].title);
        expect((await api.preparation(p.id)).documents[0]).toMatchObject({
            state: 'NEEDED',
            note: '발급 예정 · 발급 완료',
        });
        expect(cardToggle).toHaveAccessibleDescription('발급 필요');
        expect(document.querySelector('[data-active] > button')).toBe(
            cardToggle,
        );
        await userEvent.click(cardToggle);
        await userEvent.click(
            screen.getByRole('button', { name: '준비 완료' }),
        );
        await waitFor(() =>
            expect(cardToggle).toHaveAttribute('aria-expanded', 'false'),
        );
        await userEvent.click(
            screen.getByRole('button', { name: '준비 완료' }),
        );
        await waitFor(() =>
            expect(document.querySelector('[data-active="true"]')).toBeNull(),
        );
        await waitFor(() =>
            expect(window.scrollTo).toHaveBeenLastCalledWith({
                top: 0,
                behavior: 'instant',
            }),
        );
    });
    it('공고 상세에서 해당 공고에 저장한 신청 완료 상태를 표시한다', async () => {
        await api.login('demo@example.invalid', 'demo');
        const preparation = await api.start('1', 1);
        await api.schedule(
            preparation.id,
            preparation.schedules[0].id,
            preparation.revision,
            true,
        );
        mount('/notices/1');
        expect(await screen.findByText('신청 완료로 기록함')).toBeVisible();
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
    it.each([false, true])(
        '일정은 펼쳐도 완료 버튼과 상태 배지가 없고 기존 기록을 유지한다 (%s)',
        async (recorded) => {
            await api.login('demo@example.invalid', 'demo');
            let p = await api.start('1', 1);
            if (recorded)
                p = await api.schedule(
                    p.id,
                    p.schedules[0].id,
                    p.revision,
                    true,
                );
            const before = structuredClone(p.schedules);
            mount('/preparations/' + p.id + '/schedule');
            await userEvent.click(
                await screen.findByLabelText('일정 근거와 안내 펼치기'),
            );
            for (const event of p.schedules) {
                const summary = screen.getByText(event.title, {
                    selector: 'summary h3',
                });
                await userEvent.click(summary);
                const card = summary.closest('details')!;
                expect(card.open).toBe(true);
                expect(
                    within(card).queryByRole('button'),
                ).not.toBeInTheDocument();
                expect(
                    within(card).queryByText(/완료.*기록/),
                ).not.toBeInTheDocument();
            }
            expect((await api.preparation(p.id)).schedules).toEqual(before);
            await userEvent.click(
                within(
                    screen.getByRole('navigation', { name: '개인 준비' }),
                ).getByRole('link', { name: '개요' }),
            );
            expect(
                await screen.findByRole('button', {
                    name: recorded ? '신청 완료' : '신청 여부 기록',
                }),
            ).toBeVisible();
        },
    );
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
