import { afterEach, expect, it } from 'vitest';
import {
    cleanup,
    fireEvent,
    render,
    screen,
    within,
} from '@testing-library/react';
import { IndividualAnnouncement } from './IndividualAnnouncement';
import { NoticeDates } from './NoticeFacts';
import { individualAnnouncement, selectSchedule } from './schedules';
import { calendarItems } from '../preparation/calendar';
import type { Notice, Preparation } from './types';
import { TimelineScheduleCard } from './ScheduleCard';
import { buildScheduleTimeline } from '../preparation/scheduleTimelineModel';
import { comparisonSchedules } from '../comparison/comparisonModel';

it('선착순 모집은 날짜·개별 안내 대신 발표와 서류 마감에 선착순을 표시한다', () => {
    const source = {
        title: '행복주택 선착순 동호지정',
        schedules: [
            {
                ...event,
                id: 'result',
                title: '서류 제출 대상자 발표',
                date: '2026-11-25T16:00:00+09:00',
            },
            {
                ...event,
                id: 'documents',
                title: '서류 제출 마감',
                date: '2026-11-26',
            },
        ],
    } as Notice;
    const original = structuredClone(source);
    const view = render(<NoticeDates notice={source} />);
    expect(screen.getAllByText('선착순')).toHaveLength(2);
    expect(screen.queryByText('2026.11.25')).toBeNull();
    expect(screen.queryByText('16:00')).toBeNull();
    view.unmount();
    const preparation = {
        version: source.version,
        title: source.title,
        schedules: [],
    } as unknown as Preparation;
    const rows = buildScheduleTimeline(source, preparation).rows.filter(
        (row) => row.id === 'result' || row.id === 'documents',
    );
    render(
        <>
            {rows.map((row) => (
                <TimelineScheduleCard key={row.id} row={row} />
            ))}
        </>,
    );
    expect(screen.getAllByText('선착순')).toHaveLength(2);
    expect(screen.queryByLabelText('날짜 확인 필요')).toBeNull();
    expect(source).toEqual(original);
});

const event = {
    id: 'result-individual',
    title: '결과 발표 · 개별 안내',
    date: '',
    timingDescription:
        '소득·자산 확인이 필요한 경우, 신청일부터 최소 10주가 소요됩니다.\n선정 결과는 문자와 이메일로 안내합니다.',
    evidence: {
        label: '공고문 4쪽',
        url: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68869337#page=4',
        checkedAt: '2026-11-10',
    },
};
afterEach(() => {
    cleanup();
});

it('날짜가 없거나 일반 설명만 있는 결과를 개별 안내로 표시하지 않는다', () => {
    expect(
        individualAnnouncement([{ ...event, timingDescription: null }]),
    ).toBeUndefined();
    expect(
        individualAnnouncement([{ ...event, title: '결과 발표' }]),
    ).toBeUndefined();
    expect(individualAnnouncement([])).toBeUndefined();
});

it('서류 대상자 발표일과 최종 개별 안내를 함께 보존하고 공고별 설명을 사용한다', () => {
    const individual = {
        ...event,
        timingDescription: '최종 선정 결과는 자격검증 후 개별 안내합니다.',
    };
    const dated = {
        ...event,
        id: 'document-result',
        title: '서류제출 대상자 발표',
        date: '2026-11-23',
        timingDescription: null,
    };
    render(
        <NoticeDates
            notice={{ schedules: [dated, individual] } as unknown as Notice}
        />,
    );
    expect(screen.getByText('2026.11.23')).toBeVisible();
    expect(
        screen.getByRole('button', { name: '결과 발표 개별 안내 설명' }),
    ).toBeVisible();
    expect(screen.queryByText(/최소 10주/)).toBeNull();
});

it('개별 안내를 누르면 조건부 기간과 출처를 열고 닫을 수 있다', () => {
    Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
        configurable: true,
        value: function (this: HTMLDialogElement) {
            this.setAttribute('open', '');
        },
    });
    Object.defineProperty(HTMLDialogElement.prototype, 'close', {
        configurable: true,
        value: function (this: HTMLDialogElement) {
            this.removeAttribute('open');
        },
    });
    render(<IndividualAnnouncement event={event} />);
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.click(
        screen.getByRole('button', { name: '결과 발표 개별 안내 설명' }),
    );
    expect(
        screen.getByRole('dialog', { name: '결과 발표 · 개별 안내' }),
    ).toBeVisible();
    expect(
        screen.getByText(/소득·자산 확인이 필요한 경우, 신청일부터 최소 10주/),
    ).toBeVisible();
    expect(screen.getByRole('link', { name: /공고문 4쪽/ })).toHaveAttribute(
        'href',
        '/pdf-viewer.html?file=68869337&page=4',
    );
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('dialog')).toBeNull();
});

it('개별 안내와 날짜가 있는 발표를 분리해 보존하고 안내를 날짜로 취급하지 않는다', () => {
    expect(individualAnnouncement([event])).toBe(event);
    expect(selectSchedule([event], 'result')).toBeUndefined();
    const dated = { ...event, date: '2027-01-05', timingDescription: null };
    expect(individualAnnouncement([event, dated])).toBe(event);
    expect(selectSchedule([event, dated], 'result')).toBe(dated);
    const notice = {
        startDate: null,
        endDate: null,
        schedules: [event],
        evidence: event.evidence,
    } as unknown as Notice;
    expect(calendarItems(notice)).toEqual([]);
});

it.each(['공고 요약', '내 준비 전체 일정'])(
    '%s에서 순위별 두 발표일과 근거를 대상별 안내로 연다',
    (view) => {
        Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
            configurable: true,
            value: function (this: HTMLDialogElement) {
                this.setAttribute('open', '');
            },
        });
        const events = [
            {
                ...event,
                id: 'priority-result',
                title: '1순위 우선 결과 발표 (17:00 이후)',
                date: '2026-12-22T17:00',
                timingDescription: null,
            },
            {
                ...event,
                id: 'general-result',
                title: '1순위 일반·2순위 결과 발표 (17:00 이후)',
                date: '2027-01-27T17:00',
                timingDescription: null,
            },
        ];
        const notice = {
            version: 1,
            guideState: 'AVAILABLE',
            questions: [],
            schedules: events,
            evidence: event.evidence,
        } as unknown as Notice;
        const preparation = {
            version: 1,
            guideState: 'AVAILABLE',
            questions: [],
            answers: {},
            documents: [],
            schedules: events.map((item) => ({ ...item, recorded: false })),
        } as unknown as Preparation;
        if (view === '공고 요약') render(<NoticeDates notice={notice} />);
        else {
            const row = buildScheduleTimeline(notice, preparation).rows.find(
                (item) => item.id === 'result',
            )!;
            expect(row.date).toBeNull();
            render(<TimelineScheduleCard row={row} />);
        }
        fireEvent.click(
            screen.getByRole('button', { name: '결과 발표 대상별 안내' }),
        );
        const dialog = screen.getByRole('dialog', { name: '대상별 결과 발표' });
        expect(
            within(dialog).getByText('1순위 우선 결과 발표 (17:00 이후)'),
        ).toBeVisible();
        expect(within(dialog).getByText('2026.12.22 17:00')).toBeVisible();
        expect(
            within(dialog).getByText('1순위 일반·2순위 결과 발표 (17:00 이후)'),
        ).toBeVisible();
        expect(within(dialog).getByText('2027.01.27 17:00')).toBeVisible();
        expect(
            within(dialog).getAllByRole('link', { name: /공고문 4쪽/ }),
        ).toHaveLength(2);
        expect(calendarItems(notice).map((item) => item.date)).toEqual([
            '2026-12-22T17:00',
            '2027-01-27T17:00',
        ]);
        expect(
            comparisonSchedules(notice)
                .filter((item) => item.title.includes('결과 발표'))
                .map((item) => item.date),
        ).toEqual(['2026-12-22T17:00', '2027-01-27T17:00']);
    },
);
