import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { PreparationScrollSection } from './PreparationScrollSection';

const scrollToCard = vi.fn();
vi.mock('../../shared/ui/useScrollToCard', () => ({
    useScrollToCard: () => ({ cardRef: { current: null }, scrollToCard }),
}));

it('조건과 일정 카드 클릭 및 펼침은 스크롤하고 내부 버튼과 링크는 제외한다', () => {
    render(
        <>
            <PreparationScrollSection as="article">
                <h2>조건 카드</h2>
                <details>
                    <summary>조건 근거</summary>
                    <a
                        href="#source"
                        onClick={(event) => event.preventDefault()}
                    >
                        원문
                    </a>
                </details>
            </PreparationScrollSection>
            <PreparationScrollSection>
                <h2>일정 카드</h2>
                <details>
                    <summary>일정 근거</summary>
                </details>
                <button>캘린더</button>
            </PreparationScrollSection>
        </>,
    );
    const condition = screen.getByText('조건 근거').closest('details')!;
    fireEvent.click(screen.getByText('조건 카드'));
    expect(condition.open).toBe(true);
    fireEvent.click(screen.getByText('원문'));
    expect(condition.open).toBe(true);
    fireEvent.click(condition.parentElement!);
    expect(condition.open).toBe(false);
    const schedule = screen.getByText('일정 근거').closest('details')!;
    fireEvent.click(screen.getByText('일정 카드'));
    fireEvent.click(screen.getByText('캘린더'));
    expect(schedule.open).toBe(true);
    fireEvent.click(screen.getByText('일정 근거'));
    expect(schedule.open).toBe(false);
    expect(scrollToCard).toHaveBeenCalledTimes(4);
});
