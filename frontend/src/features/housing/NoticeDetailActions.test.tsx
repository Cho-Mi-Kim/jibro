import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { NoticeDetailActions } from './NoticeDetailActions';
import { startPath } from '../preparation/startIntent';
import type { Notice } from './types';

vi.mock('../preparation/startIntent', () => ({
    startPath: vi.fn(() => '/notices/notice/start?version=3'),
}));
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
    summary: '공식 공고 안내입니다.',
    version: 3,
    guideVersion: 3,
    guideState: 'AVAILABLE',
    canStart: true,
    evidence: {
        label: '공식 공고',
        url: 'https://apply.lh.or.kr/notice',
        checkedAt: '2026-11-04',
    },
    questions: [],
    documents: [],
};
function Location() {
    const location = useLocation();
    return (
        <output aria-label="현재 경로">
            {location.pathname + location.search}
        </output>
    );
}
function content(value: Notice, preparationId?: string) {
    return (
        <MemoryRouter initialEntries={['/notices/notice']}>
            <NoticeDetailActions notice={value} preparationId={preparationId} />
            <Location />
        </MemoryRouter>
    );
}

describe('공고 상세 주요 행동', () => {
    it('준비 이어하기는 해당 준비 기록으로 바로 이동한다', async () => {
        render(content(notice, 'my-record'));
        await userEvent.click(
            screen.getByRole('button', { name: '준비 이어하기' }),
        );
        expect(screen.getByLabelText('현재 경로')).toHaveTextContent(
            '/preparations/my-record',
        );
        expect(startPath).not.toHaveBeenCalled();
    });
    it('조회나 재렌더는 시작 의도를 만들지 않고 명시적 조건 확인 클릭에만 전달한다', async () => {
        const view = render(content(notice));
        expect(startPath).not.toHaveBeenCalled();
        view.rerender(content({ ...notice, title: '수정된 공고 제목' }));
        expect(startPath).not.toHaveBeenCalled();
        await userEvent.click(
            screen.getByRole('button', { name: '내 조건 확인하기' }),
        );
        expect(startPath).toHaveBeenCalledExactlyOnceWith(
            notice.id,
            notice.version,
        );
        expect(screen.getByLabelText('현재 경로')).toHaveTextContent(
            '/notices/notice/start?version=3',
        );
    });

    it('서버가 시작을 허용하지 않으면 준비를 만들지 않고 공식 공고를 제공한다', () => {
        render(
            content({ ...notice, canStart: false, guideState: 'REVIEWING' }),
        );
        expect(
            screen.queryByRole('button', { name: '내 조건 확인하기' }),
        ).not.toBeInTheDocument();
        expect(
            screen.getByRole('link', { name: '공고 원문 보기' }),
        ).toHaveAttribute('href', notice.evidence.url);
        expect(startPath).not.toHaveBeenCalled();
    });
});
