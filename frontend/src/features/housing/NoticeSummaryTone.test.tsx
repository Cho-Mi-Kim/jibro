import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { NoticeDetailContent } from './NoticeDetailContent';
import type { Notice, Question } from './types';

beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
});

const evidence = {
    label: '새 공고 공식 원문',
    url: 'https://housing.example.org/notices/2027-new',
    checkedAt: '2027-01-10',
};

function newNotice(questions: Question[]): Notice {
    return {
        id: 'future-notice-without-reviewed-copy',
        title: '새 기관의 신규 주택 공고',
        agency: '새 주택기관',
        region: '전국',
        type: '공공임대',
        status: 'UPCOMING',
        publishedDate: '2027-01-10',
        startDate: '2027-02-01',
        endDate: '2027-02-03',
        attachments: [],
        summary:
            '지원 대상: 만 19세 이상 무주택가구가 대상입니다.\n지원 내용: 전용 36.5㎡ 주택 20호를 공급합니다. 보증금은 3,000,000원입니다.',
        version: 1,
        guideVersion: 1,
        guideState: 'AVAILABLE',
        canStart: true,
        evidence,
        questions,
        qualificationSummary: {
            eligibility:
                '월소득은 150% 이하입니다. 2인 가구는 180%까지 적용됩니다.',
            selection: '동점자는 추첨합니다.',
            detail: '2027.02.03.까지 제출합니다. 150%를 초과하면 신청할 수 없습니다. 원문: “추가 서류가 필요합니다.”',
            evidence,
            reviewRequired: false,
        },
        documents: ['자격 증명서'],
        documentTemplates: [
            {
                id: 'new-document',
                title: '자격 증명서',
                issuer: '공식 발급기관',
                issueUrl: null,
                method: '2027.02.03. 이후 발급합니다. 열람용은 제출할 수 없습니다.',
                evidence,
            },
        ],
        documentSummary: {
            common: {
                items: [{ id: 'new-document', title: '자격 증명서', evidence }],
                remainingCount: 0,
            },
            conditional: { items: [], remainingCount: 0 },
            reviewRequiredCount: 0,
        },
    };
}

it.each([
    { label: '질문이 아직 없는', questions: [] as Question[] },
    {
        label: '기존 세 공고와 다른 공식 출처의',
        questions: [
            {
                id: 'new-income-rule',
                title: '신규 자격 확인',
                reason: '월소득은 150% 이하입니다.',
                evidence,
            },
        ],
    },
])(
    '$label 새 공고도 핵심 요약의 문장만 해요체로 표시한다',
    async ({ questions }) => {
        const notice = newNotice(questions);
        const original = structuredClone(notice);
        render(
            <MemoryRouter>
                <NoticeDetailContent
                    notice={notice}
                    tab="summary"
                    onTabChange={vi.fn()}
                />
            </MemoryRouter>,
        );

        expect(
            screen.getByText('만 19세 이상 무주택가구가 대상이에요.'),
        ).toBeVisible();
        expect(
            screen.getByText(
                '전용 36.5㎡ 주택 20호를 공급해요. 보증금은 3,000,000원이에요.',
            ),
        ).toBeVisible();
        expect(
            screen.getByText(
                '월소득은 150% 이하예요. 2인 가구는 180%까지 적용돼요.',
            ),
        ).toBeVisible();
        expect(screen.getByText('동점자는 추첨해요.')).toBeVisible();

        await userEvent.click(
            screen.getByLabelText('자격과 순위 세부 기준 보기'),
        );
        expect(
            screen.getByText(
                '2027.02.03.까지 제출해요. 150%를 초과하면 신청할 수 없어요. 원문: “추가 서류가 필요합니다.”',
            ),
        ).toBeVisible();

        const documents = screen
            .getByRole('heading', { name: '필요한 서류 확인' })
            .closest('section')!;
        expect(
            within(documents).getByText('자격 증명서', { selector: 'strong' }),
        ).toBeVisible();
        expect(within(documents).queryByText('제출 대상')).toBeNull();
        await userEvent.click(
            screen.getByLabelText('서류 요약과 제출 기준 보기'),
        );
        expect(within(documents).getByText('모든 신청자')).toBeVisible();
        expect(
            within(documents).getByText(
                '공식 발급기관 · 2027.02.03. 이후 발급해요. 열람용은 제출할 수 없어요.',
            ),
        ).toBeVisible();
        expect(
            within(documents).getByRole('link', { name: '자격 증명서' }),
        ).toHaveAttribute('href', evidence.url);
        expect(notice).toEqual(original);
    },
);

it('새 공고도 짧은 핵심 요약 뒤에 순위·가구원 표와 서류 예외를 펼쳐 볼 수 있다', async () => {
    const notice = newNotice([]);
    notice.summary =
        '새 지역의 분양전환형 임대주택 공고입니다. 자격 기준일은 2027.01.10입니다. 무주택세대구성원이 대상입니다. 공급목록은 20호이며 보증금은 3,000,000원입니다. 미성년 세대주는 예외에 해당하면 신청할 수 있습니다. 신청은 2월 1~3일입니다. 우편 신청은 받지 않습니다. 계약은 개별 안내입니다.';
    notice.qualificationSummary!.detail =
        '1순위는 신생아가구, 2순위는 6세 이하 자녀가 있는 한부모가족입니다.\n소득은 가구원 수별로 확인합니다.\n1인 가구 · 소득 3,000,000원 이하\n2인 가구 · 소득 4,500,000원 이하\n다만 태아도 포함합니다.\n가족관계 증빙은 기관에 확인해요.';
    notice.documentTemplates![0].method =
        '2027.02.03. 이후 발급합니다. 신청자가 서명합니다. 14세 미만은 법정대리인이 서명합니다. 열람용은 제출할 수 없습니다.';
    const original = structuredClone(notice);
    render(
        <MemoryRouter>
            <NoticeDetailContent
                notice={notice}
                tab="summary"
                onTabChange={vi.fn()}
            />
        </MemoryRouter>,
    );

    const support = screen
        .getByRole('heading', { name: '어떤 지원인가요?' })
        .closest('section')!;
    expect(
        within(support).getByText(
            /자격 기준일은 2027.01.10.*미성년 세대주는 예외/u,
        ),
    ).toBeVisible();
    expect(
        within(support).getByText('우편 신청은 받지 않아요.'),
    ).not.toBeVisible();
    await userEvent.click(screen.getByLabelText('지원 내용 공식 근거 보기'));
    expect(within(support).getByText('신청은 2월 1~3일이에요.')).toBeVisible();
    expect(within(support).getByText('우편 신청은 받지 않아요.')).toBeVisible();

    await userEvent.click(screen.getByLabelText('자격과 순위 세부 기준 보기'));
    const ranks = screen.getByRole('table', { name: '순위별 신청 대상' });
    expect(
        within(ranks).getByRole('cell', { name: '신생아가구' }),
    ).toBeVisible();
    expect(
        within(ranks).getByRole('cell', {
            name: '6세 이하 자녀가 있는 한부모가족이에요.',
        }),
    ).toBeVisible();
    const income = screen.getByRole('table', { name: '가구원 수별 기준' });
    expect(
        within(income).getByRole('cell', { name: '소득 3,000,000원 이하' }),
    ).toBeVisible();
    expect(
        within(income).getByRole('cell', { name: '소득 4,500,000원 이하' }),
    ).toBeVisible();
    expect(screen.getByText('다만 태아도 포함해요.')).toBeVisible();

    await userEvent.click(screen.getByLabelText('서류 요약과 제출 기준 보기'));
    const documents = screen
        .getByRole('heading', { name: '필요한 서류 확인' })
        .closest('section')!;
    for (const text of [
        '신청자가 서명해요.',
        '14세 미만은 법정대리인이 서명해요.',
        '열람용은 제출할 수 없어요.',
    ])
        expect(
            within(documents).getByText(text, { selector: 'li' }),
        ).toBeVisible();
    expect(
        within(documents).getByRole('link', { name: '자격 증명서' }),
    ).toHaveAttribute('href', evidence.url);
    expect(notice).toEqual(original);
});
