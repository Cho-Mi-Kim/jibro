import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { NoticeSummaryCard } from './NoticeSummaryCard';

const scrollToCard = vi.fn();
vi.mock('./useNoticeDetailScroll', () => ({
    useNoticeDetailScroll: () => ({ cardRef: { current: null }, scrollToCard }),
}));

it('카드 본문과 펼치기·접기는 정렬하고 근거 링크는 따로 정렬하지 않는다', () => {
    render(
        <NoticeSummaryCard
            title="요약"
            rows={[{ text: '내용' }]}
            explanationLabel="근거 보기"
        >
            <a href="#source" onClick={(event) => event.preventDefault()}>
                근거
            </a>
        </NoticeSummaryCard>,
    );
    fireEvent.click(screen.getByText('내용'));
    expect(scrollToCard).toHaveBeenCalledTimes(1);
    const toggle = screen.getByLabelText('근거 보기');
    fireEvent.click(toggle);
    fireEvent.click(toggle);
    expect(scrollToCard).toHaveBeenCalledTimes(3);
    fireEvent.click(screen.getByText('근거'));
    expect(scrollToCard).toHaveBeenCalledTimes(3);
});
