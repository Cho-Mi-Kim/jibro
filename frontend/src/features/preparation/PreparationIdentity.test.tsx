import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { Preparation } from '../housing/types';
import { NoticeTitle } from '../housing/NoticeTitle';
import { PreparationIdentity } from './PreparationIdentity';

const preparation: Preparation = {
    id: 'prep-1',
    revision: 1,
    questions: [],
    answers: {},
    documents: [],
    houseStage: 0,
    warnings: [],
    nextAction: { label: '', reason: '', path: '' },
    title: '2026년 청년 전세임대 1순위 입주자 수시모집',
    noticeId: 'notice-1',
    version: 1,
    progress: 0,
    guideState: 'AVAILABLE',
    schedules: [],
};

describe('준비 공고 제목', () => {
    it('홈에서 요약 제목과 공고 상세 링크를 표시한다', () => {
        render(
            <MemoryRouter>
                <PreparationIdentity
                    preparation={preparation}
                    titleContent={<NoticeTitle title={preparation.title} />}
                />
            </MemoryRouter>,
        );
        expect(screen.queryByText('신청 준비 중')).not.toBeInTheDocument();
        expect(
            screen.getByRole('heading', { name: '청년 전세임대 1순위' }),
        ).toBeVisible();
        expect(
            screen.getByRole('link', { name: /공고 정보 확인 중/ }),
        ).toHaveAttribute('href', '/notices/notice-1');
    });
    it('내 준비에서도 요약 제목으로 공고 상세에 연결한다', () => {
        render(
            <MemoryRouter>
                <PreparationIdentity preparation={preparation} />
            </MemoryRouter>,
        );
        expect(screen.queryByText('신청 준비 중')).not.toBeInTheDocument();
        expect(
            screen.getByRole('link', { name: /^청년 전세임대 1순위 / }),
        ).toHaveAttribute('href', '/notices/notice-1');
    });
    it('준비가 끝나도 개인 준비 상태 배지를 생략한다', () => {
        render(
            <MemoryRouter>
                <PreparationIdentity
                    preparation={{ ...preparation, progress: 100 }}
                />
            </MemoryRouter>,
        );
        expect(screen.queryByText('준비 기록 완료')).not.toBeInTheDocument();
    });
});
