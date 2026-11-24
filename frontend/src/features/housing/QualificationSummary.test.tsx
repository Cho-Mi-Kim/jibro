import contracts from './reviewedConditions/specialized-contracts.json';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { NoticeQualificationSummary } from './NoticeQualificationSummary';
import { ComparisonContent } from '../comparison/ComparisonContent';
import { comparisonFixture } from '../comparison/comparisonFixtures';
import { qualificationSummary } from './qualificationSummary';
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

function gyeongju(): Notice {
    const notice = comparisonFixture('gyeongju').notice;
    const sources: [string, number, string][] = [
        [
            'household',
            4,
            '상세 모집공고는 2026.11.05.을 자격 기준일로 정하고 입주 시까지 무주택을 유지하도록 합니다. 미성년 세대주 예외, 분리 배우자·예비신혼 세대 범위와 불법양도·전대 제한을 확인하세요.',
        ],
        [
            'income',
            5,
            '소득은 도시근로자 월평균소득 150% 이하로 완화합니다. 총자산 3억4,500만원·자동차 4,542만원 등 공고 기준과 자녀 가산을 확인하세요. 주택소유와 자산 기준 전체가 면제되는 공고는 아닙니다.',
        ],
        [
            'selection',
            1,
            '순위·배점 없이 주택형별 무작위 추첨으로 동호지정 순번을 정합니다. 계약 후 약 2~3개월 자격검증을 하며 적격자만 입주합니다. 정해진 방문시각·서류·계약금과 미방문 시 순번 소멸 기준을 확인하세요.',
        ],
    ];
    notice.questions = sources.map(([id, page, reason]) => {
        const original = contracts.find(
            (entry) =>
                entry.questionId === id &&
                entry.sourceReason === reason &&
                entry.sourceEvidence.url.includes('69355347'),
        )!;
        return {
            ...(original.answerContract as Pick<
                Question,
                | 'inputKind'
                | 'options'
                | 'constraints'
                | 'applicability'
                | 'answerDependencies'
            >),
            id,
            title: original.sourceTitle,
            reason,
            evidence: {
                label: `공고문 ${page}쪽`,
                url: `https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69355347#page=${page}`,
                checkedAt: '2026-11-18',
            },
        };
    });
    notice.qualificationSummary = {
        eligibility: '신청 자격은 조건 탭에서 항목별로 확인해 주세요.',
        selection: '선정 기준은 조건 탭과 공식 공고에서 확인해 주세요.',
        detail: null,
        evidence: null,
        reviewRequired: false,
    };
    return notice;
}

function mountBoth(notice: Notice) {
    render(
        <>
            <NoticeQualificationSummary
                notice={notice}
                onConditions={vi.fn()}
            />
            <ComparisonContent
                entries={[
                    { slot: 'a', data: { notice, documentRequirements: [] } },
                ]}
            />
        </>,
    );
    return [
        screen
            .getByRole('heading', { name: '자격과 순위를 구분해요' })
            .closest('section')!,
        screen.getByRole('region', { name: '신청 자격과 선정 순위' }),
    ];
}

async function expandBoth() {
    await userEvent.click(screen.getByLabelText('자격과 순위 세부 기준 보기'));
    const comparison = screen.getByRole('region', {
        name: '신청 자격과 선정 순위',
    });
    await userEvent.click(comparison.querySelector('summary')!);
}

describe('상세와 비교의 자격·순위 표시 계약', () => {
    it('경주영천의 확인된 짧은 기준·예외·수치와 PDF 쪽을 두 화면에 함께 표시한다', async () => {
        const notice = gyeongju();
        const original = structuredClone(notice);
        const areas = mountBoth(notice);
        for (const element of areas) {
            const area = within(element);
            expect(
                area.getByText('선정 방식').nextElementSibling,
            ).toHaveTextContent(
                '주택형별로 추첨하고 순번대로 동·호를 골라요. 계약 후 자격을 확인해요.',
            );
            expect(
                area.getByText('신청 자격').nextElementSibling,
            ).toHaveTextContent('월평균소득 150% 이하');
            expect(
                area.getByRole('table', { name: '함께 심사받는 가족' }),
            ).not.toBeVisible();
        }
        await expandBoth();
        for (const element of areas) {
            const area = within(element);
            expect(
                area.getByRole('table', { name: '함께 심사받는 가족' }),
            ).toHaveTextContent('배우자는 주소가 달라도 포함돼요.');
            expect(
                area.getByRole('table', { name: '가구원 수별 월소득 한도' }),
            ).toHaveTextContent('5,720,045원');
            expect(
                area.getByText('같은 등본에 있는 자녀를 부양해요.'),
            ).toBeVisible();
            expect(
                area.getByText('계약 후 약 2~3개월 동안 입주 자격을 확인해요.'),
            ).toBeVisible();
            const link = area.getByRole('link', { name: 'PDF 2쪽 보기' });
            expect(link.getAttribute('href')).toContain('file=69355347&page=2');
        }
        expect(notice).toEqual(original);
    });

    it('오산의 배점 구간·유형별 가점과 표별 근거 쪽을 두 화면에서 보존한다', async () => {
        const notice = comparisonFixture('osan').notice;
        const original = contracts.find(
            (entry) =>
                entry.questionId === 'score' &&
                entry.sourceEvidence.url.includes('69203687'),
        )!;
        const evidence = {
            label: '공고문 3–10쪽',
            url: original.sourceEvidence.url,
            checkedAt: '2026-11-18',
        };
        const detail = original.sourceReason;
        notice.qualificationSummary = {
            eligibility: '무주택세대구성원',
            selection: detail.split('\n')[0],
            detail,
            evidence,
            reviewRequired: false,
        };
        notice.questions = [
            {
                ...original.answerContract,
                id: 'score',
                title: original.sourceTitle,
                reason: detail,
                evidence,
            } as Question,
        ];
        const areas = mountBoth(notice);
        await expandBoth();
        for (const element of areas) {
            const area = within(element);
            expect(
                area.getByText('서류 대상 선정').nextElementSibling,
            ).toHaveTextContent('일반공급은 90명, 주거약자형은 15명을 뽑아요.');
            expect(
                area.getByText('서류 대상 선정').nextElementSibling,
            ).toHaveTextContent('각각 91명·16명 이상 신청하면 추첨해요.');
            const base = area.getByRole('table', { name: '기본 배점' });
            expect(
                within(base).getByRole('row', {
                    name: '1년 이상 5년 미만 14점 2점',
                }),
            ).toBeVisible();
            expect(
                area.getByRole('table', { name: '일반공급 가점' }),
            ).toHaveTextContent('생계·의료급여 수급자 중 해당 유형 없음10점');
            expect(
                area.getByRole('table', { name: '주거약자형 가점' }),
            ).toHaveTextContent('장애 정도가 심한 경우3점');
            for (const page of [9, 10]) {
                const links = area.getAllByRole('link', {
                    name: `공고문 ${page}쪽 보기`,
                });
                expect(links).toHaveLength(2);
                for (const link of links)
                    expect(link.getAttribute('href')).toContain(
                        `file=69203687&page=${page}`,
                    );
            }
            expect(
                area.queryByRole('link', { name: '공고문 3–10쪽' }),
            ).toBeNull();
        }
    });

    it.each([
        'reason',
        'source',
        'question-review',
        'issue',
        'overview-review',
    ])(
        '%s 변경 시 확인되지 않은 기준이나 근거를 이전 설명으로 대체하지 않는다',
        (change) => {
            const notice = gyeongju();
            if (change === 'overview-review')
                notice.qualificationSummary!.reviewRequired = true;
            else if (change === 'issue')
                notice.reviewIssues = [
                    {
                        id: 'pending',
                        area: 'CONDITION',
                        title: '정정본 확인 중',
                        detail: '',
                        questionIds: notice.questions.map((q) => q.id),
                        documentIds: [],
                        evidence: [],
                    },
                ];
            else
                notice.questions = notice.questions.map((question) => ({
                    ...question,
                    reason:
                        change === 'reason'
                            ? question.reason + ' 정정된 기준'
                            : question.reason,
                    reviewRequired: change === 'question-review',
                    evidence: {
                        ...question.evidence,
                        url:
                            change === 'source'
                                ? 'https://example.org/changed.pdf#page=1'
                                : question.evidence.url,
                    },
                }));
            const summary = qualificationSummary(notice);
            expect(summary.rows.map((row) => row.text).join(' ')).not.toContain(
                '150%',
            );
            expect(summary.rows[1].label).toBe('선정 순위');
            expect(summary.reviewedDetails).toEqual([]);
        },
    );
});
