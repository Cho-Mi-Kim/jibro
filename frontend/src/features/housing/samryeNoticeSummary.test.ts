import contracts from './reviewedConditions/specialized-contracts.json';
import { describe, expect, it } from 'vitest';
import {
    conditionOverview,
    noticeConditionQuestions,
} from './conditionOverview';
import { qualificationSummary } from './qualificationSummary';
import { noticeSupportSummaryRows } from './supportSummaryRows';
import {
    samryeQualificationSummary,
    samryeSubmissionSummary,
    samryeSupportSummary,
} from './samryeNoticeSummary';
import type { Notice, Question, ReviewIssue } from './types';

const pdf = 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68998510';
const sha = 'f8c62f3c12da21d2364142ae9a429303bdf30e369b1b1bcdc006d82aa303396e';
const originals: [string, number, string][] = [
    [
        'category',
        2,
        '대학생·취업준비생, 청년·사회초년생, 고령자, 주거급여수급자 중 공고에 정한 유형을 확인하세요. 사회초년생의 소득업무 종사기간은 이번 모집에서 7년 이내로 완화됩니다.',
    ],
    [
        'household',
        6,
        '대학생·청년 등 유형별 검증 범위가 다릅니다. 완주군·연접지역 밖 주택과 공고의 소형·저가주택 예외가 있으므로 단순히 주택 보유 여부만으로 판단하지 마세요.',
    ],
    [
        'vehicle',
        1,
        '소득·총자산 요건은 배제하지만 자동차가액은 4,542만원 이하이고 대학생은 자동차 미소유 기준입니다. 예외와 자산 산정 방법은 원문을 확인하세요.',
    ],
    [
        'rank',
        3,
        '각 형별 최초 공급대상자 우선, 잔여물량의 추가 공급대상자 순으로 선정합니다. 대학생의 대학·거주지, 청년의 거주지·소득근거지 기준 순위와 추첨 규칙을 확인하세요.',
    ],
    [
        'renewal',
        3,
        '최초 입주 완화와 갱신 조건은 다릅니다. 무주택·소득·자산·자동차의 재계약 기준과 최대거주기간을 확인하세요.',
    ],
    [
        'method',
        14,
        '온라인은 11/9 10시~11/20 22시, 정보취약계층 현장접수는 11/19 10~16시입니다. 현장 신청자는 접수 시 서류를 내며, 온라인 서류제출대상자는 12/1~4에 제출합니다.',
    ],
    ['applies-mydata', 19, 'MyMy서비스를 이용하려 하나요?'],
];
const questions: Question[] = originals.map(([id, page, reason]) => {
    const original = contracts.find(
        (entry) =>
            entry.questionId === id &&
            entry.sourceReason === reason &&
            entry.sourceEvidence.url.startsWith(pdf),
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
            url: `${pdf}#page=${page}`,
            checkedAt: '2026-11-20',
        },
    };
});
const schedules = [
    [
        'visit',
        '2026-11-19',
        '접수 회차 · 방문 대행(정보취약계층) 10:00~16:00 · 12:00~13:00 제외',
    ],
    ['subject', '2026-12-01', '서류 제출 대상자 발표 (16:00 이후)'],
    ['docs-start', '2026-12-01', '서류 제출 시작'],
    ['docs-end', '2026-12-04', '서류 제출 마감 (등기우편 소인 기준)'],
].map(([id, date, title]) => ({
    id,
    date,
    title,
    evidence: questions[5].evidence,
}));

function notice(overrides: Partial<Notice> = {}): Notice {
    return {
        id: 'ef03d490-e2d2-4247-9df9-65d53d10f043',
        title: '완주삼례A-1 행복주택 입주자격완화 예비입주자 모집',
        agency: 'LH',
        region: '전북',
        type: '행복주택',
        status: 'OPEN',
        publishedDate: '2026-10-19',
        startDate: '2026-11-09',
        endDate: '2026-11-20',
        attachments: [],
        summary: '지원 대상: 원래 지원 대상 지원 내용: 원래 지원 내용',
        qualificationSummary: {
            eligibility: '원래 신청 자격',
            selection: '원래 선정 순위',
            detail: null,
            evidence: null,
            reviewRequired: false,
        },
        version: 7,
        guideVersion: 7,
        guideState: 'AVAILABLE',
        canStart: true,
        evidence: questions[0].evidence,
        questions,
        schedules,
        documents: [],
        ...overrides,
    };
}

function changedQuestion(id: string, transform: (q: Question) => Question) {
    return questions.map((q) => (q.id === id ? transform(q) : q));
}

function issue(
    id: string,
    area: ReviewIssue['area'] = 'CONDITION',
): ReviewIssue {
    return {
        id: 'pending',
        area,
        title: '공식 확인 필요',
        detail: '최신 정정본 대조 중',
        questionIds: [id],
        documentIds: [],
        evidence: [],
    };
}

describe('완주삼례의 원문 검토 요약', () => {
    it('이번 모집의 21A·26A 면적과 예비자 수를 원문 쪽과 함께 표시한다', () => {
        const result = samryeSupportSummary(notice());
        expect(noticeSupportSummaryRows(notice())).toEqual(result?.rows);
        expect(result?.rows[1].text).toContain('예비입주자 190명');
        const housing = result?.detail.sections.find(
            (s) => s.table?.title === '이번에 모집하는 주택',
        );
        expect(housing?.table?.rows).toEqual([
            ['21A', '21.52㎡', '140명'],
            ['26A', '26.06㎡', '50명'],
        ]);
        expect(housing?.evidence[0].url).toBe(`${pdf}#page=4&sha256=${sha}`);
        expect(result?.detail.text).toContain('기존 대기자 다음 순번');
        expect(result?.detail.text).toContain('실제 계약할 때 바뀔 수 있어요');
        expect(result?.detail.text).not.toContain('44.28');
    });

    it('대상별 우선순위와 학생·청년의 지역 순위를 구분해 공유 요약에 전달한다', () => {
        const result = samryeQualificationSummary(notice());
        expect(qualificationSummary(notice()).rows).toEqual(result?.rows);
        expect(result?.rows[0].text).toContain('소득·총자산 기준을 빼고');
        const tables = result?.details.flatMap((detail) =>
            detail.sections.flatMap((section) => section.table ?? []),
        );
        expect(
            tables?.find((table) => table.title === '주택형별 공급대상 순서')
                ?.rows,
        ).toEqual([
            ['21A · 140명', '대학생·청년', '고령자·주거급여수급자'],
            ['26A · 50명', '고령자·주거급여수급자', '대학생·청년'],
        ]);
        expect(
            tables?.find(
                (table) => table.title === '대학생·청년의 일반공급 순위',
            )?.rows,
        ).toHaveLength(3);
        const category = conditionOverview(questions[0], []);
        expect(category.detail).toContain('합계 7년 이내');
        expect(category.detail).toContain('현재 혼인 중이 아니어야');
        expect(category.detail).toContain('만 65세 이상');
    });

    it.each(['other-file', 'page', 'digest', 'reason'])(
        '%s가 달라지면 검토한 문구를 덮어쓰지 않는다',
        (change) => {
            const changed = changedQuestion('category', (q) => ({
                ...q,
                reason:
                    change === 'reason' ? q.reason + ' 정정된 기준' : q.reason,
                evidence: {
                    ...q.evidence,
                    url:
                        change === 'other-file'
                            ? q.evidence.url!.replace('68998510', '69203687')
                            : change === 'page'
                              ? `${pdf}#page=3`
                              : change === 'digest'
                                ? `${pdf}#page=2&sha256=${'0'.repeat(64)}`
                                : q.evidence.url,
                },
            }));
            const altered = notice({ questions: changed });
            const fallback = conditionOverview(changed[0], []).structured;
            expect(
                fallback?.sections.some((section) => section.table),
            ).not.toBe(true);
            expect(
                fallback?.sections.flatMap((section) => section.evidence) ?? [],
            ).toEqual([]);
            expect(fallback?.text).not.toContain('21A');
            expect(fallback?.text).not.toContain('26A');
            expect(samryeSupportSummary(altered)).toBeUndefined();
            expect(samryeQualificationSummary(altered)).toBeUndefined();
            expect(noticeSupportSummaryRows(altered)[0].text).toBe(
                '원래 지원 대상',
            );
            expect(qualificationSummary(altered).rows[0].text).toBe(
                '원래 신청 자격',
            );
        },
    );

    it('검수중·조건 재확인 상태에서는 요약 대신 원래 검수 상태를 따른다', () => {
        for (const altered of [
            notice({ guideState: 'REVIEWING' }),
            notice({ id: 'another-notice' }),
            notice({ guideVersion: 8 }),
            notice({ publishedDate: '2026-11-20' }),
            notice({
                questions: changedQuestion('category', (q) => ({
                    ...q,
                    reviewRequired: true,
                })),
            }),
            notice({ reviewIssues: [issue('category')] }),
        ]) {
            expect(samryeSupportSummary(altered)).toBeUndefined();
            expect(samryeQualificationSummary(altered)).toBeUndefined();
        }
        expect(
            conditionOverview(questions[0], [issue('category')]).structured,
        ).toBeUndefined();
        expect(
            samryeQualificationSummary(
                notice({
                    qualificationSummary: {
                        ...notice().qualificationSummary!,
                        reviewRequired: true,
                    },
                }),
            ),
        ).toBeUndefined();
        const hashed = notice({
            questions: questions.map((q) => ({
                ...q,
                evidence: {
                    ...q.evidence,
                    url: `${q.evidence.url}&sha256=${sha}`,
                },
            })),
        });
        expect(samryeSupportSummary(hashed)).toBeDefined();
    });

    it('서류 제출 시점을 접수 방법에 맞춰 나누고 일정 재검수 때는 가린다', () => {
        const submission = samryeSubmissionSummary(notice());
        expect(submission?.text).toContain('선정되면 12월 1~4일에 내요.');
        expect(submission?.text).toContain('11월 19일 신청할 때 함께 내요.');
        expect(submission?.text).toContain('12월 4일 우체국 소인');
        expect(submission?.sections[0].evidence[0].url).toBe(
            `${pdf}#page=14&sha256=${sha}`,
        );
        expect(
            samryeSubmissionSummary(
                notice({ reviewIssues: [issue('schedule', 'SCHEDULE')] }),
            ),
        ).toBeUndefined();
        for (const change of [
            { date: '2026-12-05' },
            { title: '서류 제출 마감 (17:00)' },
            { timingDescription: '마감일 17:00까지 방문 제출' },
        ]) {
            expect(
                samryeSubmissionSummary(
                    notice({
                        schedules: schedules.map((s) =>
                            s.id === 'docs-end' ? { ...s, ...change } : s,
                        ),
                    }),
                ),
            ).toBeUndefined();
        }
    });

    it('재계약과 수급자 자동차의 확정할 수 없는 부분은 확인 안내와 양쪽 근거를 남긴다', () => {
        const renewal = conditionOverview(
            questions.find((q) => q.id === 'renewal')!,
            [],
        );
        expect(renewal.detail).toContain('두 번째');
        expect(renewal.detail).toContain('LH 확인이 필요해요');
        expect(
            renewal.structured?.sections.flatMap((s) =>
                s.evidence.map((e) => e.url),
            ),
        ).toEqual(
            expect.arrayContaining([
                `${pdf}#page=4&sha256=${sha}`,
                `${pdf}#page=7&sha256=${sha}`,
            ]),
        );
        const vehicle = conditionOverview(
            questions.find((q) => q.id === 'vehicle')!,
            [],
        );
        expect(vehicle.detail).toContain('주거급여수급자는');
        expect(vehicle.detail).toContain('LH에 확인해요');
        expect(vehicle.detail).toContain('4,542만 원');
    });

    it('공개 목록의 MyMy만 숨기고 질문·답변 의존 데이터와 다른 원문은 보존한다', () => {
        const before = structuredClone(questions);
        const publicQuestions = noticeConditionQuestions(questions);
        expect(publicQuestions.map((q) => q.id)).not.toContain(
            'applies-mydata',
        );
        expect(publicQuestions).toHaveLength(questions.length - 1);
        expect(questions).toEqual(before);
        const other = changedQuestion('applies-mydata', (q) => ({
            ...q,
            evidence: {
                ...q.evidence,
                url: 'https://example.org/other-notice',
            },
        }));
        expect(noticeConditionQuestions(other).map((q) => q.id)).toContain(
            'applies-mydata',
        );
    });
});
