import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import type { Notice, Preparation } from '../housing/types';
import { ScheduleNextCard } from './ScheduleNextCard';

const evidence = {
    label: '공고문 일정',
    url: 'https://apply.lh.or.kr/',
    checkedAt: '2026-11-06',
};
const closing = {
    id: 'closing',
    title: '신청 접수 마감 (18:00, 조기 중단 가능)',
    date: '2026-12-31',
    recorded: false,
    evidence,
};
const notice: Notice = {
    id: 'notice',
    title: '청년 전세임대 모집',
    agency: 'LH',
    region: '전국',
    type: '청년 전세임대',
    status: 'OPEN',
    startDate: '2026-02-24',
    endDate: '2026-12-31',
    publishedDate: '2026-02-24',
    attachments: [],
    summary: '',
    version: 3,
    guideVersion: 3,
    guideState: 'AVAILABLE',
    canStart: true,
    evidence,
    questions: [],
    documents: [],
    schedules: [closing],
};
const preparation: Preparation = {
    id: 'preparation',
    noticeId: notice.id,
    title: notice.title,
    version: 3,
    revision: 1,
    guideState: 'AVAILABLE',
    questions: [],
    answers: {},
    documents: [],
    schedules: [closing],
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
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-06T00:00:00+09:00'));
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
        configurable: true,
        writable: true,
        value: function (this: HTMLDialogElement) {
            this.setAttribute('open', '');
        },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
        configurable: true,
        writable: true,
        value: function (this: HTMLDialogElement) {
            this.removeAttribute('open');
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
    vi.useRealTimers();
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

function mount(overrides: Partial<Notice> = {}, records = preparation) {
    render(
        <MemoryRouter>
            <ScheduleNextCard
                notice={{ ...notice, ...overrides }}
                preparation={records}
            />
        </MemoryRouter>,
    );
}

describe('다음 중요한 일정 카드', () => {
    it('전체 일정의 주요 단계 중 다음 일정을 표시하고 가까운 접수 회차는 제외한다', () => {
        vi.setSystemTime(new Date('2026-11-23T23:00:00+09:00'));
        const schedules = [
            {
                ...closing,
                id: 'start',
                title: '신청 접수 시작',
                date: '2026-11-23T09:00:00',
            },
            {
                ...closing,
                id: 'round',
                title: '접수 회차 · 현장 접수',
                date: '2026-11-24T09:00:00',
            },
            { ...closing, date: '2026-11-25T18:00:00' },
        ];
        mount(
            { startDate: '2026-11-23', endDate: '2026-11-25', schedules },
            { ...preparation, schedules },
        );
        expect(
            screen.getByRole('heading', { name: '신청 접수 마감' }),
        ).toBeVisible();
        expect(screen.getByText('D-2')).toBeVisible();
        expect(screen.getByText('2026.11.25 18:00')).toBeVisible();
        expect(
            screen.queryByText('접수 회차 · 현장 접수'),
        ).not.toBeInTheDocument();
    });

    it.each(['CLOSED', 'EARLY_CLOSED', 'CANCELLED'] as const)(
        '공식 마감·취소 상태 %s에서는 미래 접수 일정을 추천하지 않는다',
        (state) => {
            mount(
                state === 'CLOSED'
                    ? { status: 'CLOSED' }
                    : {
                          lifecycle: {
                              state,
                              reason: '공식 마감·취소',
                              evidence,
                              checkedAt: evidence.checkedAt,
                              revision: 1,
                          },
                      },
            );
            expect(
                screen.getByText('예정된 공식 일정이 없습니다.'),
            ).toBeVisible();
        },
    );

    it('선택한 접수 방법의 일정과 공통 일정만 다음 일정과 캘린더에 함께 표시한다', async () => {
        const schedules = [
            {
                ...closing,
                id: 'visit',
                title: '접수 회차 · 현장 접수 시작',
                date: '2026-11-10T09:00:00',
            },
            {
                ...closing,
                id: 'online',
                title: '접수 회차 · 온라인 접수 시작',
                date: '2026-11-11T10:00:00',
            },
            {
                ...closing,
                id: 'result',
                title: '결과 발표',
                date: '2026-11-30',
            },
        ];
        mount(
            { schedules },
            {
                ...preparation,
                questions: [
                    {
                        id: 'method',
                        title: '어떤 방법으로 신청하나요?',
                        reason: '공식 접수 방법',
                        evidence,
                        options: [
                            { value: 'ONLINE', label: '온라인으로 신청해요' },
                            { value: 'VISIT', label: '현장에서 신청해요' },
                            { value: 'UNKNOWN', label: '잘 모르겠어요' },
                        ],
                    },
                ],
                answers: { method: 'ONLINE' },
                schedules,
            },
        );
        expect(
            screen.getByRole('heading', {
                name: '신청 접수 시작',
            }),
        ).toBeVisible();
        await userEvent.click(
            screen.getByRole('button', { name: '캘린더 내보내기' }),
        );
        const dialog = within(
            screen.getByRole('dialog', { name: '캘린더 내보내기' }),
        );
        expect(
            dialog.queryByRole('checkbox', { name: /현장 접수 시작/ }),
        ).not.toBeInTheDocument();
        expect(
            dialog.getByRole('checkbox', {
                name: '신청 접수 시작 · 2026.11.11 10:00',
            }),
        ).toBeChecked();
        expect(
            dialog.getByRole('checkbox', { name: /결과 발표/ }),
        ).toBeChecked();
    });

    it('전체 일정과 같은 제목과 확인된 마감 시각을 표시한다', () => {
        mount();
        expect(
            screen.getByRole('heading', { name: '신청 접수 마감' }),
        ).toBeVisible();
        expect(screen.getByText('2026.12.31 18:00')).toBeVisible();
        expect(screen.getByText('D-55')).toBeVisible();
    });

    it('캘린더 버튼으로 일정 선택 팝업을 열고 닫는다', async () => {
        mount();
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        const trigger = screen.getByRole('button', { name: '캘린더 내보내기' });
        expect(trigger).toHaveAttribute('aria-haspopup', 'dialog');
        await userEvent.click(trigger);
        const dialog = screen.getByRole('dialog', { name: '캘린더 내보내기' });
        const options = within(dialog);
        expect(
            options.getByRole('checkbox', {
                name: '신청 접수 마감 (18:00, 조기 중단 가능) · 2026.12.31 18:00',
            }),
        ).toBeChecked();
        expect(
            options.getByRole('button', { name: '선택 일정 .ics 받기' }),
        ).toBeEnabled();
        await userEvent.click(options.getByRole('button', { name: '닫기' }));
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('공고 기준이 바뀌면 이전 준비 시간 대신 현재 공개 일정의 시각을 보여준다', () => {
        mount({
            version: 4,
            guideState: 'REVIEWING',
            schedules: [{ ...closing, title: '신청 접수 마감 (17:00)' }],
        });
        expect(
            screen.getByRole('heading', { name: '신청 접수 마감' }),
        ).toBeVisible();
        expect(screen.getByText('2026.12.31 17:00')).toBeVisible();
        expect(
            screen.queryByText('18:00, 조기 중단 가능'),
        ).not.toBeInTheDocument();
    });

    it('예정 일정이 없어도 알림과 캘린더 동작을 제공한다', () => {
        mount(
            { endDate: '2026-01-01', schedules: [] },
            { ...preparation, schedules: [] },
        );
        expect(screen.getByText('예정된 공식 일정이 없습니다.')).toBeVisible();
        expect(screen.getByRole('link', { name: '알림 설정' })).toHaveAttribute(
            'href',
            '/alerts',
        );
        expect(
            screen.getByRole('button', { name: '캘린더 내보내기' }),
        ).toBeEnabled();
        expect(screen.queryByText('D-55')).not.toBeInTheDocument();
    });
});
