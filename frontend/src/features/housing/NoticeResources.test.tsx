import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { expect, it, vi } from 'vitest';
import type { Notice } from './types';
import { NoticeResources } from './NoticeDetailActions';

const scrollToCard = vi.fn();
vi.mock('./useNoticeDetailScroll', () => ({
    useNoticeDetailScroll: () => ({ cardRef: { current: null }, scrollToCard }),
}));

it('공고 자료와 비교를 펼치거나 접을 때 스크롤한다', () => {
    const notice = {
        id: '1',
        version: 1,
        canStart: false,
        attachments: [],
    } as unknown as Notice;
    render(
        <MemoryRouter>
            <NoticeResources notice={notice} />
        </MemoryRouter>,
    );
    const toggle = screen.getByText('공고 자료와 비교');
    fireEvent.click(toggle);
    fireEvent.click(toggle);
    expect(scrollToCard).toHaveBeenCalledTimes(2);
});
