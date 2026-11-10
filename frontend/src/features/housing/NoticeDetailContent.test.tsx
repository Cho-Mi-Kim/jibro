import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import {
    NoticeDetailContent,
    type NoticeDetailTab,
} from './NoticeDetailContent';
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
    label: '공고문 3쪽 · 사업 개요',
    url: 'https://apply.lh.or.kr/notice',
    checkedAt: '2026-11-04',
};
const notice: Notice = {
    id: 'notice',
    title: '청년 전세임대 모집',
    agency: 'LH',
    region: '전국',
    type: '청년 전세임대',
    status: 'OPEN',
    startDate: '2026-01-02',
    endDate: '2026-12-31',
    publishedDate: '2025-12-30',
    attachments: [],
    summary:
        '청년을 위한 임대주택 공고입니다. 세부 기준은 공식 공고를 확인하세요.',
    version: 3,
    guideVersion: 3,
    guideState: 'AVAILABLE',
    canStart: true,
    evidence,
    questions: [
        {
            id: 'residence',
            title: '거주지 기준 확인',
            reason: '주민등록상 거주지를 확인해 주세요.',
            evidence,
        },
    ],
    documents: ['주민등록등본'],
};
function mount(tab: NoticeDetailTab, overrides: Partial<Notice> = {}) {
    const onTabChange = vi.fn();
    render(
        <MemoryRouter>
            <NoticeDetailContent
                notice={{ ...notice, ...overrides }}
                tab={tab}
                onTabChange={onTabChange}
            />
        </MemoryRouter>,
    );
    return onTabChange;
}

describe('공고 상세 본문', () => {
    it('검수된 요약 카드에서 조건과 서류 탭으로 이동한다', async () => {
        const change = mount('summary');
        for (const [title, action, target] of [
            ['자격과 순위를 구분해요', '조건과 공식 근거 확인', 'conditions'],
            ['필요한 서류 확인', '제출서류 확인', 'documents'],
        ]) {
            const summary = screen
                .getByRole('heading', { name: title })
                .closest('summary')!;
            expect(summary.parentElement).not.toHaveAttribute('open');
            await userEvent.click(summary);
            expect(summary.parentElement).toHaveAttribute('open');
            await userEvent.click(screen.getByRole('button', { name: action }));
            expect(change).toHaveBeenLastCalledWith(target);
        }
    });

    it('첫 설명은 유지하고 추가 설명을 펼치며 실제 근거 문서 주소로 연결한다', async () => {
        mount('summary');
        expect(
            screen.getByText('청년을 위한 임대주택 공고입니다.'),
        ).toBeVisible();
        const heading = screen.getByRole('heading', {
            name: '어떤 지원인가요?',
        });
        const details = heading.closest('details');
        expect(details).not.toHaveAttribute('open');
        await userEvent.click(heading.closest('summary')!);
        expect(details).toHaveAttribute('open');
        expect(
            screen.getByText('세부 기준은 공식 공고를 확인하세요.'),
        ).toBeVisible();
        expect(
            screen.getByRole('link', { name: /공고문 3쪽 · 사업 개요/ }),
        ).toHaveAttribute('href', evidence.url);
    });

    it.each(['summary', 'conditions', 'documents'] as const)(
        '검수 중인 %s에서는 조건·서류 대신 상태와 공식 경로를 보여준다',
        (tab) => {
            const change = mount(tab, {
                guideState: 'REVIEWING',
                canStart: false,
            });
            expect(
                screen.getByRole('heading', { name: '준비 안내 검수 중' }),
            ).toBeVisible();
            expect(
                screen.getByRole('link', { name: /공식 공고 보기/ }),
            ).toHaveAttribute('href', evidence.url);
            expect(
                screen.queryByText('거주지 기준 확인'),
            ).not.toBeInTheDocument();
            expect(screen.queryByText('주민등록등본')).not.toBeInTheDocument();
            expect(
                screen.queryByRole('button', {
                    name: /자격과 순위를 구분해요/,
                }),
            ).not.toBeInTheDocument();
            expect(change).not.toHaveBeenCalled();
        },
    );

    it('준비 안내가 검토 중이어도 기본 정보로 공개된 서류 일정을 표시한다', () => {
        mount('schedule', {
            guideState: 'REVIEWING',
            schedules: [
                {
                    id: 'closing',
                    title: '신청 접수 마감',
                    date: '2026-12-31',
                    evidence,
                },
                {
                    id: 'documents',
                    title: '서류 접수 마감',
                    date: '2027-01-12',
                    evidence,
                },
            ],
        });
        expect(screen.getByText('접수 마감', { selector: 'dt' })).toBeVisible();
        expect(screen.getAllByText('2027.01.12').length).toBeGreaterThan(0);
        expect(
            screen.queryByRole('checkbox', {
                name: /서류 접수 마감/,
                hidden: true,
            }),
        ).toBeInTheDocument();
        expect(screen.getByText('서류 접수 마감')).toBeInTheDocument();
    });
});
