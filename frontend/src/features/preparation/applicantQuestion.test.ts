import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import type { Answer, Question } from '../housing/types';
import { applicantQuestion, updateApplicantAnswer } from './applicantQuestion';

const base: Question = {
    id: 'applicant',
    title: '어떤 신청 유형의 자격을 확인하나요?',
    reason: '신청 유형에 따라 함께 심사받는 가족과 제출 서류를 확인해요.',
    evidence: { label: '공고문', url: null, checkedAt: '2026-11-22' },
};

describe('신청자 상황을 묻는 질문', () => {
    it.each([
        ['완화된 소득 기준에 맞나요?', '내 신청 유형의 소득 기준에 맞나요?'],
        ['자산 기준에 맞나요?', '내 신청 유형의 재산 기준에 맞나요?'],
        ['소득·자산 기준에 맞나요?', '내 신청 유형의 소득·재산 기준에 맞나요?'],
    ])('원래 %s 질문의 심사 범위를 넓히지 않는다', (title, expected) => {
        const result = applicantQuestion({
            ...base,
            title,
            reason: '150% 이하 기준과 유형별 검증 생략 예외를 확인해요.',
            options: [
                {
                    value: 'MEETS',
                    label: '내 유형의 150% 이하 기준 또는 검증 생략에 해당해요',
                },
                {
                    value: 'DOES_NOT_MEET',
                    label: '해당하지 않아요',
                    requiresFollowUp: true,
                },
                {
                    value: 'UNKNOWN',
                    label: '잘 모르겠어요',
                    requiresFollowUp: true,
                },
            ],
        });
        expect(result.title).toBe(expected);
        expect(result.needsCriteria).toBe(true);
        expect(result.options[0].label).toBe(
            '내 유형의 150% 이하 기준 또는 검증 생략에 해당해요',
        );
    });
    it('실제 신청 유형 선택의 숫자·예외·추가 확인 의미를 유지한다', () => {
        const question: Question = {
            ...base,
            options: [
                { value: 'YOUTH', label: '청년 · 만 19~39세 미혼' },
                {
                    value: 'EARLY_CAREER',
                    label: '사회초년생 · 소득활동 총 7년 이내 미혼',
                },
                {
                    value: 'NEWLYWED',
                    label: '신혼부부 · 혼인 10년 이내 또는 9세 이하 자녀',
                },
                {
                    value: 'NONE',
                    label: '해당하는 유형이 없어요',
                    requiresFollowUp: true,
                },
                {
                    value: 'UNKNOWN',
                    label: '잘 모르겠어요',
                    requiresFollowUp: true,
                },
            ],
        };
        const result = applicantQuestion(question);
        expect(result.title).toBe('어떤 유형으로 신청할 예정인가요?');
        expect(result.needsCriteria).toBe(false);
        expect(result.options).toEqual([
            { value: 'YOUTH', label: '청년 · 만 19~39세 미혼' },
            {
                value: 'EARLY_CAREER',
                label: '사회초년생 · 소득활동 총 7년 이내 미혼',
            },
            {
                value: 'NEWLYWED',
                label: '신혼부부 · 혼인 10년 이내 또는 9세 이하 자녀',
            },
            {
                value: 'NONE',
                label: '해당하는 유형이 없어요',
                requiresFollowUp: true,
            },
            {
                value: 'UNKNOWN',
                label: '잘 모르겠어요',
                requiresFollowUp: true,
            },
        ]);
        expect(question.title).toBe('어떤 신청 유형의 자격을 확인하나요?');
    });

    it('신청 방법과 대리 방문자를 구분하고 없는 신청 방법을 만들지 않는다', () => {
        const question: Question = {
            ...base,
            title: '현장·대리신청 확인서류 제출 대상인가요?',
            options: [
                { value: 'SELF', label: '내가 직접 방문해요' },
                { value: 'SPOUSE', label: '배우자가 대신 방문해요' },
                { value: 'OTHER', label: '배우자 외 대리인이 방문해요' },
                { value: 'UNKNOWN', label: '잘 모르겠어요' },
            ],
        };
        expect(applicantQuestion(question).title).toBe(
            '누가 방문해서 신청하나요?',
        );
        const method = applicantQuestion({
            ...question,
            title: '어떤 방식으로 신청할 예정인가요?',
            options: [
                { value: 'ONLINE', label: '인터넷 또는 모바일' },
                ...question.options!,
            ],
        });
        expect(method.title).toBe('어떤 방법으로 신청할 예정인가요?');
        expect(method.options.map(({ value }) => value)).toEqual([
            'ONLINE',
            'SELF',
            'SPOUSE',
            'OTHER',
            'UNKNOWN',
        ]);
    });

    it('복합 기준 판별을 집 보유 여부 하나로 바꾸지 않고 비해당 신청 유형도 남긴다', () => {
        const options = [
            { value: 'MEETS', label: '내 신청 유형의 기준에 맞아요' },
            { value: 'NOT_MY_TYPE', label: '다른 유형으로 신청해요' },
            {
                value: 'DOES_NOT_MEET',
                label: '내 신청 유형의 기준에 맞지 않아요',
                requiresFollowUp: true,
            },
            {
                value: 'UNKNOWN',
                label: '잘 모르겠어요',
                requiresFollowUp: true,
            },
        ];
        const result = applicantQuestion({
            ...base,
            title: '집이 있어도 신청할 수 있나요?',
            options,
        });
        expect(result.title).toBe('내 신청 유형의 주택 보유 기준에 맞나요?');
        expect(result.needsCriteria).toBe(true);
        expect(
            result.options.map(({ value, requiresFollowUp }) => [
                value,
                requiresFollowUp,
            ]),
        ).toEqual([
            ['MEETS', undefined],
            ['NOT_MY_TYPE', undefined],
            ['DOES_NOT_MEET', true],
            ['UNKNOWN', true],
        ]);
        const acknowledgement = applicantQuestion({
            ...base,
            title: '성년·무주택세대구성원 기준을 확인했나요?',
            options: [
                { value: 'YES', label: '확인했어요' },
                {
                    value: 'NO',
                    label: '해당하지 않아요',
                    requiresFollowUp: true,
                },
                {
                    value: 'UNKNOWN',
                    label: '잘 모르겠어요',
                    requiresFollowUp: true,
                },
            ],
        });
        expect(acknowledgement.title).toBe(
            '성년·무주택세대구성원 기준을 확인했나요?',
        );
        expect(acknowledgement.needsCriteria).toBe(true);
        expect(acknowledgement.options[0].label).toBe('확인했어요');
    });

    it('태아·입양·출생 자녀와 미성년 예외는 별도 선택으로 남기고 기준을 함께 보여 준다', () => {
        const children = applicantQuestion({
            ...base,
            title: '태아·입양 자녀 등을 함께 확인하나요?',
            inputKind: 'MULTIPLE',
            options: [
                { value: 'PREGNANT', label: '태아' },
                { value: 'ADOPTED', label: '입양 자녀' },
                { value: 'BIRTH', label: '출생 자녀' },
                { value: 'NONE', label: '해당 없어요' },
                { value: 'UNKNOWN', label: '잘 모르겠어요' },
            ],
        });
        expect(children.title).toBe('함께 확인할 자녀가 있나요?');
        expect(children.help).toBe(
            '내 상황에 해당하는 항목을 모두 골라 주세요.',
        );
        expect(children.needsCriteria).toBe(true);
        expect(children.options.map(({ value }) => value)).toEqual([
            'PREGNANT',
            'ADOPTED',
            'BIRTH',
            'NONE',
            'UNKNOWN',
        ]);
        const age = applicantQuestion({
            ...base,
            title: '공고일 현재 성년자이거나 미성년 신청 예외에 해당하나요?',
            options: [
                { value: 'ADULT', label: '만 19세 이상' },
                {
                    value: 'MINOR_CHILD',
                    label: '같은 등본의 자녀를 부양하는 미성년 세대주',
                    requiresFollowUp: true,
                },
                {
                    value: 'NO',
                    label: '해당하지 않아요',
                    requiresFollowUp: true,
                },
                {
                    value: 'UNKNOWN',
                    label: '잘 모르겠어요',
                    requiresFollowUp: true,
                },
            ],
        });
        expect(age.needsCriteria).toBe(true);
        expect(age.help).toContain('기준일과 미성년자 예외');
        expect(age.options[1]).toEqual({
            value: 'MINOR_CHILD',
            label: '같은 등본의 자녀를 부양하는 미성년 세대주',
            requiresFollowUp: true,
        });
    });

    it('선택 계약이 없는 질문과 처음 보는 선택은 원래 의미를 유지한다', () => {
        const unknown = applicantQuestion({
            ...base,
            title: '2026년 11월 2일 기준 오산시에 주민등록되어 있나요?',
            options: [
                {
                    value: 'SPECIAL_7',
                    label: '공고에서 정한 특별 대상 7호',
                    requiresFollowUp: true,
                },
                { value: 'UNKNOWN', label: '잘 모르겠어요' },
            ],
        });
        expect(unknown.title).toBe(
            '2026년 11월 2일 기준 오산시에 주민등록되어 있나요?',
        );
        expect(unknown.options[0]).toEqual({
            value: 'SPECIAL_7',
            label: '공고에서 정한 특별 대상 7호',
            requiresFollowUp: true,
        });
        expect(unknown.needsCriteria).toBe(true);
        const defaults = applicantQuestion({
            ...base,
            title: '나이 기준과 미성년 예외',
            options: null,
        });
        expect(defaults.title).toBe(
            '나이 기준과 미성년 예외 · 내 상황에 해당하나요?',
        );
        expect(defaults.options).toEqual([
            { value: 'YES', label: '확인했어요' },
            { value: 'NO', label: '해당하지 않아요' },
            { value: 'UNKNOWN', label: '잘 모르겠어요' },
        ]);
    });

    it('연결 때문에 유지되는 단순 안내는 개인 사실로 바꾸지 않는다', () => {
        const result = applicantQuestion({
            ...base,
            title: '2세 미만 자녀가 있으면 먼저 선정하나요?',
            options: [
                { value: 'YES', label: '확인했어요' },
                {
                    value: 'REVIEW',
                    label: '추가 확인이 필요해요',
                    requiresFollowUp: true,
                },
                {
                    value: 'UNKNOWN',
                    label: '잘 모르겠어요',
                    requiresFollowUp: true,
                },
            ],
        });
        expect(result.title).toBe('2세 미만 자녀가 있으면 먼저 선정하나요?');
        expect(result.help).toBe(
            '다음 준비에 필요한 안내예요. 내용을 읽고 확인한 상태를 골라 주세요.',
        );
        expect(result.needsCriteria).toBe(true);
        expect(result.options).toEqual([
            { value: 'YES', label: '확인했어요' },
            {
                value: 'REVIEW',
                label: '추가 확인이 필요해요',
                requiresFollowUp: true,
            },
            {
                value: 'UNKNOWN',
                label: '잘 모르겠어요',
                requiresFollowUp: true,
            },
        ]);
    });
});

describe('검토한 포항 공고의 신청 유형별 입력', () => {
    const questions = JSON.parse(
        readFileSync(
            '../data/notices/pohang-bluevalley-answer-consistency-20261121.json',
            'utf8',
        ),
    ).content.questions as Question[];
    const household = questions.find((q) => q.id === 'household')!;
    const income = questions.find((q) => q.id === 'income-assets')!;

    it.each(['YOUTH', 'EARLY_CAREER'])(
        '%s는 집만 본인 기준으로 묻고 기존 예외 충족 답변을 유지한다',
        (category) => {
            const before = structuredClone(questions);
            const context = { questions, answers: { category } };
            const prompt = applicantQuestion(household, context);
            expect(prompt.title).toBe('본인 명의의 집이 있나요?');
            expect(prompt.help).toContain(
                '부모 등 다른 가족의 집은 확인하지 않아요',
            );
            expect(prompt.preview).toContain('포항 밖 주택');
            expect(prompt.options).toEqual([
                {
                    value: 'MEETS',
                    label: '없거나, 공고에서 허용하는 소형·저가주택 등이 있어요',
                    requiresFollowUp: false,
                },
                {
                    value: 'DOES_NOT_MEET',
                    label: '허용 기준에 해당하지 않는 집이 있어요',
                    requiresFollowUp: true,
                },
                {
                    value: 'UNKNOWN',
                    label: '잘 모르겠어요',
                    requiresFollowUp: true,
                },
            ]);
            const assets = applicantQuestion(income, context);
            expect(assets.title).toBe('자동차 기준에 맞나요?');
            expect(assets.preview).toContain(
                '자동차는 가족을 함께 확인할 수 있어요',
            );
            expect(assets.options[0].label).toBe(
                '자동차가 없거나, 한도·가산·제외 기준에 맞아요',
            );
            expect(questions).toEqual(before);
        },
    );

    it('예비신혼의 미래 세대와 통합 일반의 완화 제외를 구분하고 산업근로자 혼인 여부를 추정하지 않는다', () => {
        expect(
            applicantQuestion(household, {
                questions,
                answers: { category: 'PREMARITAL' },
            }).title,
        ).toBe('결혼 후 함께 살 가족에게 집이 있나요?');
        for (const category of ['NEWLYWED', 'SINGLE_PARENT', 'ELDER'])
            expect(
                applicantQuestion(household, {
                    questions,
                    answers: { category },
                }).help,
            ).toContain('주소가 다른 배우자도 포함해요');
        const context = { questions, answers: { category: 'INTEGRATED' } };
        expect(applicantQuestion(household, context).preview).toBe(
            '통합공공임대 일반공급은 포항 밖 주택·소형·저가주택 완화를 적용하지 않아요.',
        );
        expect(applicantQuestion(household, context).options[0].label).toBe(
            '집이 없거나, 공고의 무주택 인정 기준에 해당해요',
        );
        expect(applicantQuestion(income, context).title).toBe(
            '소득·재산·자동차 기준에 맞나요?',
        );
        expect(
            applicantQuestion(household, {
                questions,
                answers: { category: 'INDUSTRIAL' },
            }),
        ).toEqual(applicantQuestion(household));
    });

    it('선택이 미확정이거나 원문·선택지·검수가 다르면 기존 질문으로 돌아간다', () => {
        for (const category of [
            'UNKNOWN',
            'NONE',
            'STUDENT',
            ['YOUTH'],
        ] as Answer[])
            expect(
                applicantQuestion(household, {
                    questions,
                    answers: { category },
                }),
            ).toEqual(applicantQuestion(household));
        for (const modified of [
            { ...household, reason: household.reason + ' 새 기준' },
            { ...household, reviewRequired: true },
            {
                ...household,
                evidence: {
                    ...household.evidence,
                    url: household.evidence.url + '&sha256=changed',
                },
            },
            {
                ...household,
                options: household.options!.map((o) =>
                    o.value === 'MEETS'
                        ? { ...o, label: '가족 모두 집이 없어요' }
                        : o,
                ),
            },
        ])
            expect(
                applicantQuestion(modified, {
                    questions,
                    answers: { category: 'YOUTH' },
                }),
            ).toEqual(applicantQuestion(modified));
        for (const change of [
            (q: Question) => ({ ...q, reviewRequired: true }),
            (q: Question) => ({
                ...q,
                options: q.options!.map((o) =>
                    o.value === 'YOUTH' ? { ...o, label: '일반공급' } : o,
                ),
            }),
            (q: Question) => ({
                ...q,
                options: q.options!.map((o) =>
                    o.value === 'YOUTH' ? { ...o, requiresFollowUp: true } : o,
                ),
            }),
        ]) {
            const changed = questions.map((q) =>
                q.id === 'category' ? change(q) : q,
            );
            expect(
                applicantQuestion(household, {
                    questions: changed,
                    answers: { category: 'YOUTH' },
                }),
            ).toEqual(applicantQuestion(household));
        }
    });

    it('유형을 바꾸면 기존 기준 답변만 재확인하고 같은 선택·다른 답변·원본을 보존한다', () => {
        const answers = {
            category: 'YOUTH',
            household: 'MEETS',
            'income-assets': 'DOES_NOT_MEET',
            'applies-family': 'NO_SPOUSE',
        };
        expect(
            updateApplicantAnswer(questions, answers, 'category', 'NEWLYWED'),
        ).toEqual({
            category: 'NEWLYWED',
            household: 'UNKNOWN',
            'income-assets': 'UNKNOWN',
            'applies-family': 'NO_SPOUSE',
        });
        expect(
            updateApplicantAnswer(questions, answers, 'category', 'UNKNOWN')
                .household,
        ).toBe('UNKNOWN');
        expect(
            updateApplicantAnswer(questions, answers, 'category', 'YOUTH'),
        ).toEqual(answers);
        expect(
            updateApplicantAnswer(
                questions,
                answers,
                'household',
                'DOES_NOT_MEET',
            ),
        ).toEqual({ ...answers, household: 'DOES_NOT_MEET' });
        expect(
            updateApplicantAnswer(
                questions,
                { category: 'YOUTH' },
                'category',
                'NEWLYWED',
            ),
        ).toEqual({ category: 'NEWLYWED' });
        const otherNotice = questions.map((q) => ({
            ...q,
            evidence: { ...q.evidence, url: null },
        }));
        expect(
            updateApplicantAnswer(otherNotice, answers, 'category', 'NEWLYWED'),
        ).toEqual({ ...answers, category: 'NEWLYWED' });
        expect(answers).toEqual({
            category: 'YOUTH',
            household: 'MEETS',
            'income-assets': 'DOES_NOT_MEET',
            'applies-family': 'NO_SPOUSE',
        });
    });
});
