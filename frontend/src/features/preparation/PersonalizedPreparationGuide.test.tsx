import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it } from 'vitest';
import { PersonalizedPreparationGuide } from './PersonalizedPreparationGuide';
import type { DocumentItem, Question } from '../housing/types';

const evidence = {
    label: '공식 모집 안내',
    url: 'https://example.org/notice.pdf#page=3',
    checkedAt: '2026-11-22',
};
const category: Question = {
    id: 'category',
    title: '어떤 유형으로 신청하나요?',
    reason: '신청할 유형을 선택해요.',
    evidence,
    options: [
        { value: 'YOUTH', label: '청년' },
        { value: 'PREMARITAL', label: '예비신혼부부' },
        { value: 'UNKNOWN', label: '잘 모르겠어요' },
    ],
};
const method: Question = {
    id: 'method',
    title: '어떤 방법으로 신청하나요?',
    reason: '인터넷 또는 현장에서 신청해요.',
    evidence,
    options: [
        { value: 'ONLINE', label: '인터넷 신청' },
        { value: 'VISIT', label: '현장 방문 신청' },
        { value: 'UNKNOWN', label: '잘 모르겠어요' },
    ],
};
const proxy: Question = {
    id: 'proxy',
    title: '누가 방문해서 신청하나요?',
    reason: '대리인이 방문하면 관계 증명을 준비해요.',
    evidence,
    options: [
        { value: 'SELF', label: '본인이 직접 신청해요' },
        { value: 'SPOUSE', label: '배우자가 대신 신청해요' },
        { value: 'UNKNOWN', label: '잘 모르겠어요' },
    ],
};
const acknowledgement: Question = {
    id: 'selection',
    title: '동호 지정과 계약은 어떻게 하나요?',
    reason: '선정된 순서대로 동호를 정해요.',
    evidence,
    options: [
        { value: 'ACK', label: '안내를 확인했어요' },
        { value: 'UNKNOWN', label: '잘 모르겠어요' },
    ],
};

function document(id: string, title: string): DocumentItem {
    return {
        id,
        title,
        necessity: 'CONDITIONAL',
        state: 'UNCHECKED',
        method: '공식 양식을 준비해요.',
        issuer: 'LH',
        issueUrl: null,
        note: '',
        evidence,
    };
}

describe('답변에 맞춘 준비 안내', () => {
    it('실제 선택한 유형·접수방법·방문자를 보이고 연결된 필요서류만 함께 정리한다', () => {
        const documents = [
            document('youth-proof', '청년 유형 증빙'),
            document('proxy-proof', '대리신청 관계 증명'),
            {
                ...document('other', '다른 유형 서류'),
                necessity: 'NOT_APPLICABLE' as const,
            },
            { ...document('pending', '검수 중 서류'), reviewRequired: true },
        ];
        render(
            <PersonalizedPreparationGuide
                guideState="AVAILABLE"
                questions={[category, method, proxy]}
                answers={{
                    category: 'YOUTH',
                    method: 'ONLINE',
                    proxy: 'SPOUSE',
                }}
                documents={documents}
                documentConditions={{
                    'youth-proof': 'category',
                    'proxy-proof': 'proxy',
                    other: 'category',
                    pending: 'category',
                }}
                documentConditionValues={{
                    'youth-proof': ['YOUTH'],
                    'proxy-proof': ['SPOUSE'],
                    other: ['YOUTH'],
                    pending: ['YOUTH'],
                }}
            />,
        );
        expect(screen.getByText('청년')).toBeVisible();
        expect(screen.getByText('인터넷 신청')).toBeVisible();
        expect(screen.getByText('배우자가 대신 신청해요')).toBeVisible();
        expect(screen.getByText('준비할 서류: 청년 유형 증빙')).toBeVisible();
        expect(
            screen.getByText('준비할 서류: 대리신청 관계 증명'),
        ).toBeVisible();
        expect(screen.queryByText(/다른 유형 서류|검수 중 서류/u)).toBeNull();
        expect(
            screen.getAllByRole('link', { name: '공식 모집 안내' })[0],
        ).toHaveAttribute('href', evidence.url);
    });

    it('일회성 결과의 서류 서식 계약도 같은 연결을 사용한다', () => {
        render(
            <PersonalizedPreparationGuide
                guideState="AVAILABLE"
                questions={[category]}
                answers={{ category: 'PREMARITAL' }}
                documents={[document('premarital', '예비신혼부부 확인서')]}
                templates={[
                    {
                        id: 'premarital',
                        title: '예비신혼부부 확인서',
                        method: '',
                        issuer: 'LH',
                        issueUrl: null,
                        evidence,
                        conditionQuestionId: 'category',
                        conditionAnswerValues: ['PREMARITAL'],
                    },
                ]}
            />,
        );
        expect(
            screen.getByText('준비할 서류: 예비신혼부부 확인서'),
        ).toBeVisible();
    });

    it('OTHER가 배우자 외 대리인을 뜻하면 선택과 연결된 위임 서류를 표시한다', () => {
        render(
            <PersonalizedPreparationGuide
                guideState="AVAILABLE"
                questions={[
                    {
                        ...proxy,
                        options: [
                            {
                                value: 'OTHER',
                                label: '배우자 외 다른 사람이 대신 신청해요',
                            },
                        ],
                    },
                ]}
                answers={{ proxy: 'OTHER' }}
                documents={[
                    document('proxy-form', '위임장'),
                    document('seal', '인감증명서'),
                ]}
                documentConditions={{ 'proxy-form': 'proxy', seal: 'proxy' }}
                documentConditionValues={{
                    'proxy-form': ['OTHER'],
                    seal: ['OTHER'],
                }}
            />,
        );
        expect(
            screen.getByText('배우자 외 다른 사람이 대신 신청해요'),
        ).toBeVisible();
        expect(
            screen.getByText('준비할 서류: 위임장 · 인감증명서'),
        ).toBeVisible();
    });

    it('제출서류 전체를 검수하는 동안 선택한 상황만 표시하고 서류를 확정하지 않는다', () => {
        render(
            <PersonalizedPreparationGuide
                guideState="AVAILABLE"
                questions={[category]}
                answers={{ category: 'YOUTH' }}
                documents={[document('youth', '청년 증빙')]}
                documentConditions={{ youth: 'category' }}
                documentConditionValues={{ youth: ['YOUTH'] }}
                reviewIssues={[
                    {
                        id: 'documents',
                        area: 'DOCUMENT',
                        title: '제출 서류 확인 중',
                        detail: '최신 공식 서식과 대조하고 있어요.',
                        questionIds: [],
                        documentIds: [],
                        evidence: [evidence],
                    },
                ]}
            />,
        );
        expect(screen.getByText('청년')).toBeVisible();
        expect(screen.queryByText(/준비할 서류/u)).toBeNull();
    });

    it.each(['UNKNOWN', 'INVALID', 'review', 'conflict'])(
        '%s 답변을 확정된 신청 유형으로 안내하지 않는다',
        (state) => {
            const family: Question = {
                ...category,
                id: 'family',
                title: '배우자가 있나요?',
                options: [{ value: 'NONE', label: '없어요' }],
                answerDependencies: [
                    {
                        questionId: 'category',
                        answerValues: ['PREMARITAL'],
                        conflictingValues: ['NONE'],
                        requiredDocumentIds: ['premarital'],
                        message:
                            '예비신혼부부 답변과 가족관계 답변을 다시 확인해요.',
                    },
                ],
            };
            render(
                <PersonalizedPreparationGuide
                    guideState="AVAILABLE"
                    questions={[
                        { ...category, reviewRequired: state === 'review' },
                        family,
                    ]}
                    answers={{
                        category: ['UNKNOWN', 'INVALID'].includes(state)
                            ? state
                            : 'PREMARITAL',
                        family: 'NONE',
                    }}
                    documents={[document('premarital', '예비신혼부부 확인서')]}
                    documentConditions={{ premarital: 'category' }}
                    documentConditionValues={{ premarital: ['PREMARITAL'] }}
                />,
            );
            expect(screen.queryByText('예비신혼부부')).toBeNull();
            expect(screen.queryByText(/준비할 서류/u)).toBeNull();
            if (state === 'conflict')
                expect(
                    screen.getByText(
                        '다른 답변과 맞지 않아 다시 확인해야 해요.',
                    ),
                ).toBeVisible();
        },
    );

    it('확인했어요 답변을 실제 접수방법으로 해석하지 않는다', () => {
        render(
            <PersonalizedPreparationGuide
                guideState="AVAILABLE"
                questions={[{ ...method, options: null }]}
                answers={{ method: 'YES' }}
                documents={[]}
            />,
        );
        expect(
            screen.getByText('구체적인 선택을 확인한 뒤 준비 내용을 정리해요.'),
        ).toBeVisible();
        expect(screen.queryByText('현장 방문 신청')).toBeNull();
        expect(screen.queryByText('인터넷 신청')).toBeNull();
    });

    it.each([
        ['MEETS', '내 신청유형 기준에 맞아요'],
        ['CHECKED', '기준과 예외를 함께 확인했어요'],
        ['NONE', '해당하는 유형 없어요'],
        ['DOES_NOT_MEET', '기준에 맞지 않아요'],
        ['OTHER_CATEGORY', '다른 유형으로 신청해요'],
        ['YES', '안내 내용을 모두 확인했어요'],
    ])(
        '포괄적인 %s 확인을 구체적인 신청 유형으로 만들지 않는다',
        (value, label) => {
            render(
                <PersonalizedPreparationGuide
                    guideState="AVAILABLE"
                    questions={[{ ...category, options: [{ value, label }] }]}
                    answers={{ category: value }}
                    documents={[]}
                />,
            );
            expect(screen.queryByText(label)).toBeNull();
            expect(
                screen.getByText(
                    '구체적인 선택을 확인한 뒤 준비 내용을 정리해요.',
                ),
            ).toBeVisible();
        },
    );

    it('값이 YES여도 선택지가 명시한 실제 신청방법은 표시한다', () => {
        render(
            <PersonalizedPreparationGuide
                guideState="AVAILABLE"
                questions={[
                    {
                        ...method,
                        options: [{ value: 'YES', label: '현장 신청해요' }],
                    },
                ]}
                answers={{ method: 'YES' }}
                documents={[]}
            />,
        );
        expect(screen.getByText('현장 신청해요')).toBeVisible();
    });

    it('답변·서류에 연결되지 않은 확인 안내만 읽을 내용으로 분리한다', () => {
        const linked = {
            ...acknowledgement,
            id: 'linked',
            title: '제출 안내를 확인했나요?',
        };
        render(
            <MemoryRouter>
                <PersonalizedPreparationGuide
                    guideState="AVAILABLE"
                    questions={[acknowledgement, linked]}
                    answers={{}}
                    documents={[]}
                    documentConditions={{ form: 'linked' }}
                />
            </MemoryRouter>,
        );
        const guide = within(
            screen.getByRole('region', { name: '내 답변에 맞춘 준비 안내' }),
        );
        const groupSummary = guide.getByText('공통·맞춤 준비 안내 1개');
        const group = groupSummary.closest('details')!;
        expect(groupSummary).toBeVisible();
        expect(group.open).toBe(false);
        expect(
            guide.getByText('동호 지정과 계약은 어떻게 하나요?'),
        ).not.toBeVisible();
        fireEvent.click(groupSummary);
        expect(group.open).toBe(true);
        expect(
            guide.queryByRole('heading', { name: '제출 안내를 확인했나요?' }),
        ).toBeNull();
        const heading = guide.getByRole('heading', {
            name: '동호 지정과 계약은 어떻게 하나요?',
        });
        const disclosure = heading.closest('li')!.querySelector('details')!;
        fireEvent.click(heading);
        expect(disclosure.open).toBe(true);
        expect(group.open).toBe(true);
        fireEvent.click(heading);
        expect(disclosure.open).toBe(false);
        expect(group.open).toBe(true);
        fireEvent.click(groupSummary);
        expect(group.open).toBe(false);
        expect(heading).not.toBeVisible();
    });

    it('공고 전체 안내가 검수 중이면 맞춤 안내를 공개하지 않는다', () => {
        const { container } = render(
            <PersonalizedPreparationGuide
                guideState="REVIEWING"
                questions={[category, acknowledgement]}
                answers={{ category: 'YOUTH' }}
                documents={[]}
            />,
        );
        expect(container).toBeEmptyDOMElement();
    });
});
