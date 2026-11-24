import { expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { comparisonApi } from './comparisonApi';
import { comparisonFixture } from './comparisonFixtures';
import { ComparisonContent } from './ComparisonContent';
import { reviewedConditionPresentation } from '../housing/reviewedConditionPresentation';

it('상세를 먼저 열지 않아도 비교 응답의 공식 출처에 맞는 검토 문구를 불러온다', async () => {
    const data = comparisonFixture('0000e667-e82e-4db9-b3cf-947ba42146f8');
    data.notice.questions = [
        {
            id: 'review-residence',
            title: '공고 기준일에 해당 지역에 거주하나요?',
            reason: '공고문에 정한 기준일의 광산구·동구·서구·남구·북구 거주 여부를 확인하세요. 현재 주소만으로 판단하지 않습니다.',
            evidence: {
                label: '공고문 2쪽',
                url: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69173114#page=2',
                checkedAt: '2026-11-21',
            },
        },
    ];
    const question = data.notice.questions[0];
    expect(reviewedConditionPresentation(question)).toBeUndefined();
    vi.stubGlobal(
        'fetch',
        vi.fn(async (path: string) => {
            if (path !== `/api/v1/notices/${data.notice.id}/comparison`)
                throw new Error(`Unexpected request: ${path}`);
            return Response.json(data);
        }),
    );

    const result = await comparisonApi.notice(data.notice.id);
    render(<ComparisonContent entries={[{ slot: 'a', data: result }]} />);
    const conditions = within(screen.getByRole('region', { name: '조건' }));
    const card = conditions
        .getByRole('heading', { name: '어디에 살고 있어야 하나요?' })
        .closest('li')!;
    expect(
        within(card).getByText('공고 기준일 광산·동·서·남·북구 거주'),
    ).toBeVisible();
    await userEvent.click(within(card).getByRole('heading'));
    expect(
        within(card).getByText('현재 주소만으로 판단하지 않아요.'),
    ).toBeVisible();
    expect(
        within(card)
            .getByRole('link', { name: '공고문 2쪽 보기' })
            .getAttribute('href'),
    ).toContain('file=69173114&page=2');
    expect(result.documentRequirements).toEqual(data.documentRequirements);
});
