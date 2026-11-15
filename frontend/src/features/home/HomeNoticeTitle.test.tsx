import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useState } from 'react';
import userEvent from '@testing-library/user-event';
import { House } from '../preparation/PreparationProgress';
import { HomeNoticeTitle } from './HomeNoticeTitle';

describe('홈 공고 제목', () => {
    it('공고 목록과 같은 요약 제목을 별도 펼침 버튼 없이 표시한다', () => {
        render(
            <HomeNoticeTitle title="2026년 청년 전세임대 1순위 입주자 수시모집" />,
        );
        expect(
            screen.getByRole('heading', { name: '청년 전세임대 1순위' }),
        ).toBeVisible();
        expect(screen.queryByRole('button')).not.toBeInTheDocument();
    });
    it('준비 현황 카드의 기존 펼치기와 제목이 함께 바뀐다', async () => {
        const title = '2026년 청년 전세임대 1순위 입주자 수시모집';
        function Card() {
            const [expanded, setExpanded] = useState(false);
            return (
                <>
                    <HomeNoticeTitle title={title} expanded={expanded} />
                    <House
                        progress={0}
                        stage={0}
                        onExplanationToggle={setExpanded}
                    />
                </>
            );
        }
        render(<Card />);
        const toggle = screen.getByLabelText('준비도 설명');
        expect(
            screen.getByRole('heading', { name: '청년 전세임대 1순위' }),
        ).toBeVisible();
        await userEvent.click(toggle);
        expect(
            await screen.findByRole('heading', { name: title }),
        ).toBeVisible();
        await userEvent.click(toggle);
        expect(
            await screen.findByRole('heading', { name: '청년 전세임대 1순위' }),
        ).toBeVisible();
    });
});
