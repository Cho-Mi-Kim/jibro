import { expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { Guide } from '../housing/types';
import { GuideAnswer } from './GuideAnswer';

const guide: Guide = {
    id: 'guide-documents',
    category: '서류 발급 안내',
    title: '어떤 서류를 준비하나요?',
    body: '공고에서 요구한 서류를 준비해요.',
    scope: '신청할 공고의 서류 목록을 확인해요.',
    evidence: {
        label: '공식 발급 안내',
        url: 'https://www.gov.kr/',
        checkedAt: '2026-11-21',
    },
};

it('다시 확인할 내용은 이전 본문 앞에서 알리고 내용 확인일과 시도일을 구분한다', () => {
    render(
        <GuideAnswer
            guide={{
                ...guide,
                freshness: {
                    status: 'RECHECK',
                    checkedAt: '2026-11-21',
                    lastAttemptAt: '2026-11-22T16:30:00Z',
                    message: 'GEMINI_QUOTA_INTERNAL_REASON',
                },
            }}
        />,
    );
    const notice = screen.getByLabelText('가이드 확인 상태');
    const body = screen.getByText(guide.body);
    expect(notice).toHaveTextContent('최신 내용 확인이 필요해요.');
    expect(
        notice.compareDocumentPosition(body) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(screen.getByText('마지막 내용 확인 2026-11-21')).toBeVisible();
    expect(screen.getByText('최근 확인 시도 2026-11-23')).toBeVisible();
    expect(screen.getByText('확인 2026-11-21')).toBeVisible();
    expect(
        screen.queryByText('GEMINI_QUOTA_INTERNAL_REASON'),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('link')).toHaveAttribute(
        'href',
        guide.evidence.url,
    );
});

it('출처가 열리지 않아도 마지막 본문·링크·내용 확인일을 보존한다', () => {
    render(
        <GuideAnswer
            guide={{
                ...guide,
                freshness: {
                    status: 'UNREACHABLE',
                    checkedAt: '2026-11-21',
                    lastAttemptAt: '2026-11-22',
                    message: 'HTTP_503',
                },
            }}
        />,
    );
    expect(screen.getByText('공식 자료에 연결하지 못했어요.')).toBeVisible();
    expect(screen.getByText(guide.body)).toBeVisible();
    expect(screen.getByText('확인 2026-11-21')).toBeVisible();
    expect(screen.queryByText('확인 2026-11-22')).not.toBeInTheDocument();
    expect(screen.queryByText('HTTP_503')).not.toBeInTheDocument();
});

it.each([
    undefined,
    {
        status: 'CURRENT',
        checkedAt: '2026-11-22',
        lastAttemptAt: '2026-11-22',
    } satisfies Guide['freshness'],
])(
    '정상 확인 또는 정적 원고에서는 재확인 표시나 임의 날짜를 만들지 않는다',
    (freshness) => {
        render(<GuideAnswer guide={{ ...guide, freshness }} />);
        expect(
            screen.queryByLabelText('가이드 확인 상태'),
        ).not.toBeInTheDocument();
        expect(screen.getByText(guide.body)).toBeVisible();
        expect(screen.getByText('확인 2026-11-21')).toBeVisible();
        expect(screen.queryByText(/최근 확인 시도/)).not.toBeInTheDocument();
    },
);

it('날짜가 없거나 잘못됐을 때 현재 날짜로 대체하지 않는다', () => {
    render(
        <GuideAnswer
            guide={{
                ...guide,
                freshness: { status: 'RECHECK', lastAttemptAt: 'not-a-date' },
            }}
        />,
    );
    expect(screen.getByText('최신 내용 확인이 필요해요.')).toBeVisible();
    expect(
        screen.queryByText(/최근 확인 시도|마지막 내용 확인|Invalid Date/),
    ).not.toBeInTheDocument();
    expect(screen.getByText('확인 2026-11-21')).toBeVisible();
});

it('문장은 줄을 나누고 날짜·시간·소수·예외와 기존 문단은 보존한다', () => {
    const body =
        '2026. 11. 21. 기준으로 확인해요. 상담은 09:00~18:00이에요. 소득 한도는 1.5배까지예요. 다만 예외 대상은 별도 서류를 내요.\n\n서류에 “상세”라고 적혀 있나요? 그 항목을 표시해요.';
    const { container } = render(<GuideAnswer guide={{ ...guide, body }} />);
    const paragraphs = Array.from(container.querySelectorAll('p'));
    expect(paragraphs[0].textContent).toBe(
        '2026. 11. 21. 기준으로 확인해요.\n상담은 09:00~18:00이에요.\n소득 한도는 1.5배까지예요.\n다만 예외 대상은 별도 서류를 내요.',
    );
    expect(paragraphs[1].textContent).toBe(
        '서류에 “상세”라고 적혀 있나요?\n그 항목을 표시해요.',
    );
    expect(paragraphs[0].textContent?.replace(/\s+/gu, '')).toBe(
        body.split('\n\n')[0].replace(/\s+/gu, ''),
    );
    expect(screen.getByRole('link')).toHaveAttribute(
        'href',
        guide.evidence.url,
    );
});

it('기존 목록의 항목 수를 유지하며 각 항목과 적용 범위에서도 문장을 나눈다', () => {
    const { container } = render(
        <GuideAnswer
            guide={{
                ...guide,
                body: '- 신분증을 준비해요. 대리 신청은 위임장도 필요해요.\n- 발급일을 확인해요.\n\n1. 공고를 확인해요. 마감일도 확인해요.\n2. 서류를 제출해요.',
                scope: '일반적인 발급 안내예요. 공고에서 정한 제출 기준을 따라요.',
            }}
        />,
    );
    expect(screen.getAllByRole('list')).toHaveLength(2);
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(4);
    expect(items[0].textContent).toBe(
        '신분증을 준비해요.\n대리 신청은 위임장도 필요해요.',
    );
    expect(items[2].textContent).toBe('공고를 확인해요.\n마감일도 확인해요.');
    expect(container.querySelector('p')?.textContent).toBe(
        '적용 범위일반적인 발급 안내예요.\n공고에서 정한 제출 기준을 따라요.',
    );
});
