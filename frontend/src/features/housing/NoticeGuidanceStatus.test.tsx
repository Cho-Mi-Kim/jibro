import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { guideLabels } from './guideLabels';
import { notices } from './demo';
import { NoticeGuidanceStatus } from './NoticeGuidanceStatus';
import type { Notice } from './types';

const publishedNotice = notices.find(
    (notice) => notice.guideState === 'AVAILABLE',
)!;

function renderStatus(overrides: Partial<Notice> = {}) {
    render(
        <NoticeGuidanceStatus notice={{ ...publishedNotice, ...overrides }} />,
    );
    const summary = screen.getByText('준비 안내 확인 현황').closest('summary')!;
    return { summary, details: summary.parentElement! };
}

describe('준비 안내 확인 현황', () => {
    it('펼치면 기본 정보·준비 안내 검수·자동 수집 시각을 구별해서 보여준다', async () => {
        const user = userEvent.setup();
        const { summary, details } = renderStatus({
            evidence: { ...publishedNotice.evidence, checkedAt: '2026-10-01' },
            basicCheckedAt: '2026-11-03T01:02:00Z',
            guideCheckedAt: '2026-11-04T02:03:00Z',
            collectedAt: '2026-11-05T03:04:00Z',
        });

        expect(details).not.toHaveAttribute('open');
        await user.tab();
        expect(summary).toHaveFocus();
        await user.click(summary);
        expect(details).toHaveAttribute('open');
        expect(details).toHaveTextContent(/조건.*확인 질문 2개/);
        expect(details).toHaveTextContent(/서류.*제출서류 목록 2종/);
        expect(details).toHaveTextContent(/일정.*접수 기간 확인됨/);
        expect(details).toHaveTextContent(
            /기본 정보 확인.*2026\. 11\. 03\. 10:02/,
        );
        expect(details).toHaveTextContent(
            /준비 안내 검수.*2026\. 11\. 04\. 11:03/,
        );
        expect(details).toHaveTextContent(/자동 수집.*2026\. 11\. 05\. 12:04/);
        expect(details).not.toHaveTextContent('2026-10-01');
        await user.click(summary);
        expect(details).not.toHaveAttribute('open');
    });

    it('검수 시각이 없으면 출처 확인일이나 자동 수집 시각으로 대신하지 않는다', async () => {
        const { summary, details } = renderStatus({
            basicCheckedAt: null,
            guideCheckedAt: null,
            collectedAt: '2026-11-05T03:04:00Z',
            evidence: { ...publishedNotice.evidence, checkedAt: '2026-10-01' },
        });
        await userEvent.click(summary);
        expect(details.textContent?.match(/확인 기록 없음/g)).toHaveLength(2);
        expect(details).toHaveTextContent(/자동 수집.*2026\. 11\. 05\. 12:04/);
        expect(details).not.toHaveTextContent('2026-10-01');
    });

    it.each(['REVIEWING', 'CHANGE_REVIEW', 'UNSUPPORTED'] as const)(
        '%s 상태에서는 조건·서류 데이터가 남아 있어도 공개 항목으로 세지 않는다',
        async (guideState) => {
            const { summary, details } = renderStatus({ guideState });
            await userEvent.click(summary);
            expect(details).toHaveTextContent(guideLabels[guideState]);
            expect(details).not.toHaveTextContent(
                /확인 질문 \d+개|제출서류 목록 \d+종/,
            );
            expect(details).toHaveTextContent('접수 기간 확인됨');
        },
    );

    it('준비 안내가 공개됐어도 비어 있는 조건·서류를 확인된 항목으로 세지 않는다', async () => {
        const { summary, details } = renderStatus({
            questions: [],
            documents: [],
        });
        await userEvent.click(summary);
        expect(details).toHaveTextContent('공개된 질문 없음');
        expect(details).toHaveTextContent('공개된 서류 목록 없음');
    });

    it('접수일이 없어도 준비 안내 상태와 별개인 공개 서류 일정은 표시한다', async () => {
        const { summary, details } = renderStatus({
            guideState: 'REVIEWING',
            startDate: null,
            endDate: null,
            schedules: [
                {
                    id: 'documents',
                    title: '서류 제출 마감',
                    date: '2026-12-10',
                    evidence: publishedNotice.evidence,
                },
            ],
        });
        await userEvent.click(summary);
        expect(details).toHaveTextContent('공개된 일정 있음');
        expect(details).not.toHaveTextContent('확인된 일정 없음');
    });
});
