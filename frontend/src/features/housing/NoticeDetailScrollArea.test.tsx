import { fireEvent, render, screen } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import {
    NoticeDetailScrollDetails,
    NoticeDetailScrollSection,
} from './NoticeDetailScrollArea';

const scrollToCard = vi.fn();
vi.mock('./useNoticeDetailScroll', () => ({
    useNoticeDetailScroll: () => ({ cardRef: { current: null }, scrollToCard }),
}));

it('조건·서류·일정 영역과 원문 발췌를 선택하면 스크롤하고 링크와 버튼은 제외한다', () => {
    render(
        <>
            <NoticeDetailScrollSection>
                <h2>조건</h2>
                <details>
                    <summary>조건 펼치기</summary>
                    <a
                        href="#source"
                        onClick={(event) => event.preventDefault()}
                    >
                        원문
                    </a>
                </details>
            </NoticeDetailScrollSection>
            <NoticeDetailScrollSection>
                <h2>제출서류</h2>
                <details>
                    <summary>서류 펼치기</summary>
                </details>
            </NoticeDetailScrollSection>
            <NoticeDetailScrollSection>
                <h2>일정</h2>
                <details>
                    <summary>일정 펼치기</summary>
                </details>
                <button>내보내기</button>
            </NoticeDetailScrollSection>
            <NoticeDetailScrollDetails>
                <summary>원문 발췌</summary>
            </NoticeDetailScrollDetails>
        </>,
    );
    for (const label of [
        '조건',
        '조건 펼치기',
        '서류 펼치기',
        '일정 펼치기',
        '원문 발췌',
    ])
        fireEvent.click(screen.getByText(label));
    expect(scrollToCard).toHaveBeenCalledTimes(5);
    fireEvent.click(screen.getByText('원문'));
    fireEvent.click(screen.getByText('내보내기'));
    expect(scrollToCard).toHaveBeenCalledTimes(5);
});
