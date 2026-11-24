import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { applicantQuestions, answerableQuestions } from './questions';
import { answerDemoPreparation } from './demoPreparationRules';
import type { Preparation, Question } from './types';

const cases = JSON.parse(
    readFileSync('../data/contracts/question-roles.json', 'utf8'),
) as {
    name: string;
    questions: Question[];
    documentQuestionIds: string[];
    applicantQuestionIds: string[];
    answerableQuestionIds: string[];
}[];

describe('신청자 상황 질문과 확인 안내의 공통 계약', () => {
    it('시연도 서류와 연결된 확인 답변으로 서류를 확정하고 다른 안내의 기존 답변은 보존한다', () => {
        const question = cases[0].questions[0];
        const p: Preparation = {
            id: 'p',
            noticeId: 'n',
            title: '준비',
            version: 1,
            revision: 1,
            guideState: 'AVAILABLE',
            questions: [
                { ...question, id: 'guide' },
                { ...question, id: 'document-check' },
            ],
            answers: { guide: 'UNKNOWN' },
            documentConditions: { d: 'document-check' },
            documents: [
                {
                    id: 'd',
                    title: '서류',
                    necessity: 'UNKNOWN',
                    state: 'UNCHECKED',
                    method: '확인 후 제출',
                    issuer: '기관',
                    issueUrl: null,
                    note: '준비 메모',
                    evidence: question.evidence,
                },
            ],
            schedules: [],
            progress: 0,
            houseStage: 1,
            warnings: [],
            nextAction: { label: '', reason: '', path: '' },
        };
        expect(
            answerableQuestions(
                p.questions,
                Object.values(p.documentConditions!),
            ).map((item) => item.id),
        ).toEqual(['document-check']);
        const updated = answerDemoPreparation(
            p,
            { ...p.answers, 'document-check': 'ACK' },
            { d: { questionId: 'document-check', answerValues: ['ACK'] } },
        );
        expect(updated.documents[0].necessity).toBe('CONDITIONAL');
        expect(updated.documents[0].note).toBe('준비 메모');
        expect(updated.answers.guide).toBe('UNKNOWN');
        expect(p.documents[0].necessity).toBe('UNKNOWN');
    });

    it.each(cases)('$name', (entry) => {
        const original = structuredClone(entry.questions);
        expect(
            applicantQuestions(entry.questions, entry.documentQuestionIds).map(
                (question) => question.id,
            ),
        ).toEqual(entry.applicantQuestionIds);
        expect(
            answerableQuestions(entry.questions, entry.documentQuestionIds).map(
                (question) => question.id,
            ),
        ).toEqual(entry.answerableQuestionIds);
        expect(entry.questions).toEqual(original);
    });
});
