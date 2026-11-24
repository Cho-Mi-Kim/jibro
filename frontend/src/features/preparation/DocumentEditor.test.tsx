import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { expect, it, vi } from 'vitest';
import type { DocumentItem, Preparation } from '../housing/types';
import { DocumentEditor } from './DocumentEditor';

const scrollToCard = vi.fn();
vi.mock('../../shared/ui/useScrollToCard', () => ({
    useScrollToCard: () => {
        const cardRef = { current: null };
        return { cardRef, scrollToCard: () => scrollToCard(cardRef.current) };
    },
}));
vi.mock('./usePreparation', () => ({ useSaved: () => vi.fn() }));
vi.mock('../../shared/ui/useUnsavedChanges', () => ({
    useUnsavedChanges: () => {},
}));

it('서류 카드 본문으로 접고 펼칠 때마다 스크롤한다', async () => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
    const document = {
        id: 'application',
        title: '공급신청서',
        necessity: 'CONDITIONAL',
        state: 'UNCHECKED',
        issuer: '발급처',
        method: '',
        note: '',
        evidence: { label: '공고문', url: null, checkedAt: '2026-11-13' },
    } as DocumentItem;
    const preparation = {
        id: 'p',
        revision: 1,
        documentConditions: { application: 'family' },
        documentConditionValues: { application: ['ENGAGED'] },
        questions: [
            {
                id: 'family',
                title: '신청 유형',
                options: [
                    { value: 'MARRIED', label: '신혼부부' },
                    { value: 'ENGAGED', label: '예비신혼부부' },
                ],
            },
        ],
        guideState: 'AVAILABLE',
        reviewIssues: [],
    } as unknown as Preparation;
    function Example() {
        const [active, setActive] = useState(false);
        return (
            <DocumentEditor
                p={preparation}
                d={document}
                active={active}
                onSelect={() => setActive(!active)}
            />
        );
    }
    render(
        <QueryClientProvider client={new QueryClient()}>
            <Example />
        </QueryClientProvider>,
    );
    const button = screen.getByRole('button', { name: /공급신청서/ });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveAccessibleName('공급신청서');
    expect(button).toHaveAccessibleDescription('미확인');
    expect(screen.getByText('제출 대상')).not.toBeVisible();
    expect(screen.getByText('발급 방법')).not.toBeVisible();
    await userEvent.click(button);
    expect(screen.getByText('예비신혼부부')).toBeVisible();
    expect(screen.queryByText('신혼부부')).not.toBeInTheDocument();
    expect(screen.getByText('발급 방법')).toBeVisible();
    expect(button).toHaveAttribute('aria-expanded', 'true');
    const summary = screen.getByText('상태·메모 수정');
    const options = summary.closest('details')!;
    await userEvent.click(summary);
    await waitFor(() => expect(scrollToCard).toHaveBeenCalledTimes(2));
    expect(scrollToCard).toHaveBeenLastCalledWith(options);
    await userEvent.selectOptions(screen.getByRole('combobox'), 'NEEDED');
    await userEvent.type(screen.getByRole('textbox'), '발급 예정');
    expect(scrollToCard).toHaveBeenCalledTimes(2);
    await userEvent.click(summary);
    await waitFor(() => expect(scrollToCard).toHaveBeenCalledTimes(3));
    expect(scrollToCard).toHaveBeenLastCalledWith(
        button.closest('[data-active]'),
    );
    expect(options.open).toBe(false);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    await userEvent.click(screen.getByText('예비신혼부부'));
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByText('예비신혼부부')).not.toBeVisible();
    expect(scrollToCard).toHaveBeenCalledTimes(4);
});

it('기존 준비 기록에도 검증된 온라인 발급 링크를 표시한다', () => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
    const document = {
        id: 'resident',
        title: '신청자 주민등록표등본',
        necessity: 'REQUIRED',
        state: 'UNCHECKED',
        issuer: '행정복지센터',
        issueUrl: null,
        method: '',
        note: '',
        evidence: { label: '공고문', url: null, checkedAt: '2026-11-13' },
    } as DocumentItem;
    const preparation = {
        id: 'p',
        noticeId: '06462f53-d47e-419e-b458-c8a6e212b96a',
        revision: 1,
        guideState: 'AVAILABLE',
        reviewIssues: [],
    } as unknown as Preparation;
    render(
        <QueryClientProvider client={new QueryClient()}>
            <DocumentEditor
                p={preparation}
                d={document}
                active
                onSelect={() => {}}
            />
        </QueryClientProvider>,
    );
    const facts = screen.getByText('발급 방법').closest('dl')!;
    expect(within(facts).getByText('행정복지센터')).toBeVisible();
    expect(within(facts).getByText('모든 신청자')).toBeVisible();
    expect(
        screen.getByRole('link', { name: /정부24 주민등록표 발급/ }),
    ).toHaveAttribute(
        'href',
        'https://www.gov.kr/mw/AA020InfoCappView.do?CappBizCD=13100000015',
    );
});
