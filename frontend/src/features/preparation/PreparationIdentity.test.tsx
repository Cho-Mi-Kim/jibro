import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { Preparation } from '../housing/types';
import { HomeNoticeTitle } from '../home/HomeNoticeTitle';
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
                    titleContent={<HomeNoticeTitle title={preparation.title} />}
                />
            </MemoryRouter>,
        );
        expect(
            screen.getByRole('heading', { name: '청년 전세임대 1순위' }),
        ).toBeVisible();
        expect(
            screen.getByRole('link', { name: /공고 정보 확인 중/ }),
        ).toHaveAttribute('href', '/notices/notice-1');
    });
    it('내 준비에서는 기존 원래 제목 링크를 유지한다', () => {
        render(
            <MemoryRouter>
                <PreparationIdentity preparation={preparation} />
            </MemoryRouter>,
        );
        expect(
            screen.getByRole('link', { name: /2026년 청년 전세임대/ }),
        ).toHaveAttribute('href', '/notices/notice-1');
    });
});
