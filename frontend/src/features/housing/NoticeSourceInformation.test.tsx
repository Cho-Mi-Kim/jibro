import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NoticeDetailContent } from './NoticeDetailContent';
import { NoticeDetailActions } from './NoticeDetailActions';
import { NoticeSourceInformation } from './NoticeSourceInformation';
import type { NoticeDetailTab } from './noticeDetailTabs';
import type { NoticeSourceInformationData } from './noticeSourceInformationApi';
import type { NoticeSourceInformationState } from './useNoticeSourceInformation';
import type { Notice } from './types';

beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
});

const evidence = {
    label: 'LH 모집공고 · 3쪽',
    url: 'https://apply.lh.or.kr/source.pdf',
    checkedAt: '2026-11-09',
};
const notice: Notice = {
    id: 'reviewing',
    title: '공식 모집공고',
    agency: 'LH',
    region: '서울',
    type: '국민임대',
    status: 'UNKNOWN',
    startDate: null,
    endDate: null,
    publishedDate: '2026-11-05',
    attachments: [],
    summary: '공식 모집공고의 기본 정보입니다.',
    version: 1,
    guideVersion: null,
    guideState: 'REVIEWING',
    canStart: false,
    evidence,
    questions: [
        {
            id: 'unpublished',
            title: '미공개 개인별 질문',
            reason: '아직 확인되지 않은 답변 기준',
            evidence,
        },
    ],
    documents: ['미공개 개인별 서류'],
    schedules: [],
};
const data: NoticeSourceInformationData = {
    noticeId: notice.id,
    checkedAt: '2026-11-09T01:00:00Z',
    state: 'AVAILABLE',
    sections: [
        {
            id: 'condition',
            area: 'CONDITION',
            title: '신청자격 원문 구간',
            text: '공식 조건 본문\n다만 예외의 적용은 전체 원문을 확인합니다.',
            evidence,
        },
        {
            id: 'document',
            area: 'DOCUMENT',
            title: '제출서류 원문 구간',
            text: '공식 서류 본문\n해당하는 사람만 추가 증빙을 제출합니다.',
            evidence,
        },
        {
            id: 'schedule',
            area: 'SCHEDULE',
            title: '일정 원문 구간',
            text: '공식 일정 본문\n접수기간은 정정 원문 확인이 필요합니다.',
            evidence,
        },
    ],
    pendingAreas: [],
};
function information(overrides: Partial<NoticeSourceInformationState> = {}) {
    return {
        data,
        loading: false,
        failed: false,
        retry: vi.fn(),
        ...overrides,
    };
}
function mount(tab: NoticeDetailTab, current = notice, value = information()) {
    return render(
        <MemoryRouter>
            <NoticeDetailContent
                notice={current}
                tab={tab}
                sourceInformation={value}
                onTabChange={vi.fn()}
            />
            <NoticeDetailActions notice={current} />
        </MemoryRouter>,
    );
}

describe('검수 중 원문 확인', () => {
    it.each([
        ['conditions', '신청자격 원문 구간', '제출서류 원문 구간'],
        ['documents', '제출서류 원문 구간', '신청자격 원문 구간'],
    ] as const)(
        '%s 탭에는 해당 영역의 원문만 보여준다',
        async (tab, title, other) => {
            mount(tab);
            expect(screen.getByText('원문 발췌 · 검수 중')).toBeVisible();
            const summary = screen.getByText(title).closest('summary')!;
            expect(summary.parentElement).not.toHaveAttribute('open');
            await userEvent.click(summary);
            expect(summary.parentElement).toHaveAttribute('open');
            expect(
                screen.getByRole('link', { name: /LH 모집공고 · 3쪽/ }),
            ).toHaveAttribute('href', evidence.url);
            expect(screen.getByText('자료 확인 · 2026-11-09')).toBeVisible();
            expect(screen.queryByText(other)).not.toBeInTheDocument();
            expect(
                screen.queryByText('일정 원문 구간'),
            ).not.toBeInTheDocument();
            expect(
                screen.queryByText('미공개 개인별 질문'),
            ).not.toBeInTheDocument();
            expect(
                screen.queryByText('미공개 개인별 서류'),
            ).not.toBeInTheDocument();
            expect(
                screen.queryByRole('button', { name: '내 조건 확인하기' }),
            ).not.toBeInTheDocument();
            expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
            expect(screen.queryByRole('radio')).not.toBeInTheDocument();
        },
    );

    it('요약은 읽을 수 있는 구간과 아직 없는 영역을 구분한다', () => {
        mount(
            'summary',
            notice,
            information({
                data: {
                    ...data,
                    sections: data.sections.slice(0, 1),
                    pendingAreas: ['DOCUMENT', 'SCHEDULE'],
                },
            }),
        );
        expect(screen.getByText('신청자격 원문 구간')).toBeVisible();
        expect(
            screen.getByText('서류·일정 원문은 확인 중입니다.'),
        ).toBeVisible();
        expect(
            screen.queryByRole('heading', { name: '준비 안내 검수 중' }),
        ).not.toBeInTheDocument();
        expect(screen.queryByText('확인 질문 1개')).not.toBeInTheDocument();
    });

    it('빈 영역은 다른 영역 발췌를 재사용하지 않고 기존 안내와 공식 경로를 표시한다', () => {
        mount(
            'documents',
            notice,
            information({
                data: {
                    ...data,
                    sections: data.sections.slice(0, 1),
                    pendingAreas: ['DOCUMENT', 'SCHEDULE'],
                },
            }),
        );
        expect(
            screen.getAllByRole('heading', { name: '준비 안내 검수 중' }),
        ).toHaveLength(1);
        expect(
            screen.getByRole('link', { name: '공식 공고 보기' }),
        ).toHaveAttribute('href', evidence.url);
        expect(
            screen.queryByText('신청자격 원문 구간'),
        ).not.toBeInTheDocument();
    });

    it('공개 일정이 비었을 때만 일정 발췌를 추가하며 날짜로 확정하지 않는다', () => {
        mount('schedule');
        expect(screen.getByText('일정 원문 구간')).toBeVisible();
        expect(
            screen.queryByText('신청자격 원문 구간'),
        ).not.toBeInTheDocument();
        expect(
            screen.getByText('접수기간', { selector: 'dt' }).nextElementSibling,
        ).toHaveTextContent('확인 필요');
    });

    it('검수된 안내는 원문 미리보기로 바꾸지 않는다', () => {
        mount('conditions', {
            ...notice,
            guideState: 'AVAILABLE',
            canStart: true,
        });
        expect(screen.getByText('미공개 개인별 질문')).toBeVisible();
        expect(
            screen.queryByText('원문 발췌 · 검수 중'),
        ).not.toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: '내 조건 확인하기' }),
        ).toBeVisible();
    });

    it('원문 문자열의 HTML은 실행하지 않고 예외를 포함한 전체 발췌를 펼쳐 읽는다', async () => {
        const text =
            '<img src=x onerror=alert(1)>\n' +
            '본문 구간 '.repeat(80) +
            '\n마지막 예외 조건';
        const view = render(
            <NoticeSourceInformation
                notice={notice}
                information={information({
                    data: {
                        ...data,
                        sections: [{ ...data.sections[0], text }],
                    },
                })}
            />,
        );
        expect(view.container.querySelector('img')).toBeNull();
        const summary = screen
            .getByText('신청자격 원문 구간')
            .closest('summary')!;
        expect(summary.textContent).not.toContain('마지막 예외 조건');
        await userEvent.click(summary);
        expect(view.container.querySelector('details p')).toHaveTextContent(
            '마지막 예외 조건',
        );
        expect(view.container.querySelector('details p')).toBeVisible();
    });

    it('실패 시 이전 데이터는 숨기고 공식 경로와 재시도를 제공한다', async () => {
        const retry = vi.fn();
        render(
            <NoticeSourceInformation
                notice={notice}
                information={information({ failed: true, retry })}
            />,
        );
        expect(
            screen.queryByText('신청자격 원문 구간'),
        ).not.toBeInTheDocument();
        await userEvent.click(
            screen.getByRole('button', { name: '원문 다시 불러오기' }),
        );
        expect(retry).toHaveBeenCalledOnce();
        expect(
            screen.getByRole('link', { name: '공식 공고 보기' }),
        ).toBeVisible();
    });

    it('이미지 판독문은 원문 대조 안내와 출처를 유지하고 숫자를 고치지 않는다', async () => {
        const title = '공고문 1쪽 [OCR · 원문 대조 필요]';
        const text = '모집 159 · 76 · 합계 306';
        render(
            <NoticeSourceInformation
                notice={notice}
                information={information({
                    data: {
                        ...data,
                        sections: [{ ...data.sections[0], title, text }],
                    },
                })}
            />,
        );
        await userEvent.click(screen.getByText(title));
        expect(screen.getByText(/이미지에서 읽은 글자입니다/)).toBeVisible();
        expect(screen.getAllByText(text).at(-1)).toBeVisible();
        expect(
            screen.getByRole('link', { name: evidence.label }),
        ).toHaveAttribute('href', evidence.url);
        expect(screen.queryByText('합계 235')).not.toBeInTheDocument();
    });

    it('도면 판독은 별도 참고 자료로 보여 주고 공식 도면으로 연결한다', async () => {
        const title =
            '[OCR · 원문 대조 필요] HWP BinData/BIN0001.jpg (쪽 번호 확인 필요)';
        render(
            <NoticeSourceInformation
                notice={notice}
                information={information({
                    data: {
                        ...data,
                        sections: [
                            { ...data.sections[0], area: 'REFERENCE', title },
                        ],
                        pendingAreas: ['CONDITION', 'DOCUMENT', 'SCHEDULE'],
                    },
                })}
            />,
        );
        expect(screen.getByText('도면')).toBeVisible();
        await userEvent.click(
            screen.getByText(
                '[OCR · 원문 대조 필요] 첨부 이미지 1 (쪽 번호 확인 필요)',
            ),
        );
        expect(screen.getByText(/치수·색상·호수의 위치/)).toBeVisible();
        expect(
            screen.getByRole('link', { name: evidence.label }),
        ).toBeVisible();
    });
});
