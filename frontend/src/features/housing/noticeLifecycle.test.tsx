import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import type { Notice, NoticeLifecycleState, Preparation } from './types';
import { activeSchedules, effectiveNoticeStatus } from './noticeLifecycle';
import { NoticeDetailOverview } from './NoticeDetailOverview';
import { NoticeCardDeadline } from './NoticeCardDeadline';
import { NoticeDetailActions } from './NoticeDetailActions';
import { ScheduleNextCard } from '../preparation/ScheduleNextCard';
import { calendarItems } from '../preparation/calendar';
import { buildScheduleTimeline } from '../preparation/scheduleTimelineModel';

beforeEach(() =>
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    ),
);
afterEach(() => vi.unstubAllGlobals());

const evidence = {
    label: 'LH 공식 변경 안내',
    url: 'https://apply.lh.or.kr/notice',
    checkedAt: '2026-11-07',
};
const notice: Notice = {
    id: 'one',
    title: '청년 임대 모집',
    agency: 'LH',
    region: '서울',
    type: '청년 임대',
    status: 'OPEN',
    startDate: '2099-11-01',
    endDate: '2099-12-31',
    publishedDate: '2099-10-31',
    attachments: [],
    summary: '모집 공고',
    version: 3,
    guideVersion: 3,
    guideState: 'AVAILABLE',
    canStart: true,
    evidence,
    questions: [],
    documents: [],
    schedules: [
        {
            id: 'closing',
            title: '신청 접수 마감',
            date: '2099-12-31',
            evidence,
        },
        {
            id: 'documents',
            title: '서류 제출 마감',
            date: '2100-01-03',
            evidence,
        },
    ],
};
const preparation: Preparation = {
    id: 'prep',
    noticeId: 'one',
    title: notice.title,
    version: 3,
    revision: 1,
    guideState: 'AVAILABLE',
    questions: [],
    answers: {},
    documents: [],
    schedules: [],
    progress: 0,
    houseStage: 0,
    warnings: [],
    nextAction: { label: '', path: '', reason: '' },
};
const withState = (state: NoticeLifecycleState): Notice => ({
    ...notice,
    canStart: state === 'NORMAL',
    lifecycle: {
        state,
        reason: '운영기관 공식 안내 확인',
        evidence,
        checkedAt: '2026-11-07T00:00:00Z',
        revision: 1,
    },
});

describe('공식 조기 마감·취소', () => {
    it('신청 완료·여부 확인도 중지하되 서류·결과 발표·공고 게시를 오인하지 않는다', () => {
        const titles = [
            '신청 접수 시작',
            '신청 접수 마감',
            '공식 신청 여부 확인',
            '신청 완료 확인',
            '서류 제출 마감',
            '서류 제출 대상자 신청',
            '신청 접수 마감 결과 발표',
            '공고 접수 마감 안내 게시',
        ];
        expect(
            activeSchedules(
                withState('EARLY_CLOSED'),
                titles.map((title) => ({ title })),
            ).map((event) => event.title),
        ).toEqual(titles.slice(4));
        expect(
            activeSchedules(
                withState('CANCELLED'),
                titles.map((title) => ({ title })),
            ),
        ).toEqual([]);
    });

    it('날짜 상태를 덮어쓰지 않고 공식 상태가 목록 표시에 우선한다', () => {
        const closed = withState('EARLY_CLOSED');
        expect(closed.status).toBe('OPEN');
        expect(effectiveNoticeStatus(closed)).toBe('EARLY_CLOSED');
        const { container } = render(
            <NoticeCardDeadline
                notice={{ ...closed, partialGuidance: false }}
            />,
        );
        expect(screen.getByText('조기 마감')).toBeInTheDocument();
        expect(container.textContent).not.toMatch(/D-/);
        expect(effectiveNoticeStatus(withState('NORMAL'))).toBe('OPEN');
    });
    it('상세에서 변경 근거와 원래 날짜를 유지하고 새 시작만 막는다', () => {
        const cancelled = withState('CANCELLED');
        render(
            <MemoryRouter>
                <NoticeDetailOverview notice={cancelled} />
                <NoticeDetailActions notice={cancelled} preparationId="prep" />
            </MemoryRouter>,
        );
        expect(
            screen.getByRole('link', { name: /LH 공식 변경 안내/ }),
        ).toHaveAttribute('href', evidence.url);
        expect(
            screen.getByRole('heading', { name: '기존 공고 일정' }),
        ).toBeInTheDocument();
        expect(
            screen.getByRole('button', { name: /준비 기록 보기/ }),
        ).toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: /내 조건 확인하기/ }),
        ).not.toBeInTheDocument();
    });
    it('조기 마감은 신청 일정만, 취소는 모든 캘린더 일정을 제외한다', () => {
        expect(
            calendarItems(withState('EARLY_CLOSED')).map(
                (event) => event.title,
            ),
        ).toEqual(['서류 제출 마감']);
        expect(calendarItems(withState('CANCELLED'))).toEqual([]);
        expect(calendarItems(withState('NORMAL'))).toHaveLength(3);
    });
    it('취소 이후 다음 기한을 만들지 않고 타임라인의 기존 날짜는 취소로 표시한다', () => {
        const cancelled = withState('CANCELLED');
        render(
            <MemoryRouter>
                <ScheduleNextCard
                    notice={cancelled}
                    preparation={preparation}
                />
            </MemoryRouter>,
        );
        const card = screen.getByRole('complementary', {
            name: '다음 중요한 일정',
        });
        expect(
            within(card).getByText('예정된 공식 일정이 없습니다.'),
        ).toBeInTheDocument();
        expect(card.textContent).not.toMatch(/D-/);
        const timeline = buildScheduleTimeline(
            cancelled,
            preparation,
            new Date('2099-11-07T00:00:00Z'),
        );
        expect(timeline.rows.find((row) => row.id === 'end')).toMatchObject({
            date: '2099-12-31',
            status: '공고 취소',
            tone: 'unknown',
        });
    });
});
