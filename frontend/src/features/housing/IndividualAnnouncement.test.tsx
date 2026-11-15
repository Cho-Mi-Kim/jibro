import { afterEach, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { IndividualAnnouncement } from './IndividualAnnouncement';
import { NoticeDates } from './NoticeFacts';
import { individualAnnouncement, selectSchedule } from './schedules';
import { calendarItems } from '../preparation/calendar';
import type { Notice } from './types';

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
