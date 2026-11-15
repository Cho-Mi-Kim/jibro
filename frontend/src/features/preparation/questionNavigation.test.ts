import { describe, expect, it } from 'vitest';
import type { Answer, Question } from '../housing/types';
import { getQuestionStep } from './questionNavigation';

const questions: Question[] = ['first', 'second', 'third'].map((id) => ({
    id,
    title: id,
    reason: '',
    evidence: { label: '공고 1쪽', url: null, checkedAt: '2026-11-07' },
}));

describe('condition question navigation', () => {
    it.each<[Record<string, Answer>, number]>([
        [{}, 0],
        [{ first: 'YES' }, 1],
        [{ first: 'UNKNOWN', second: 'NO' }, 2],
        [{ first: 'YES', second: 'UNKNOWN', third: 'YES' }, 1],
        [{ first: 'YES', second: 'NO', third: 'YES' }, 0],
        [{ first: 'YES', second: 'OLD_OPTION', third: 'YES' }, 1],
    ])('resumes saved answers %j at step %i', (answers, expected) => {
        expect(getQuestionStep({ questions, answers })).toBe(expected);
    });

    it('keeps an explicitly selected question even when another is unanswered', () => {
        expect(
            getQuestionStep({ questions, answers: { first: 'YES' } }, 'first'),
        ).toBe(0);
    });

    it('falls back to the resume question for an obsolete question link', () => {
        expect(
            getQuestionStep(
                { questions, answers: { first: 'YES' } },
                'removed',
            ),
        ).toBe(1);
    });

    it('returns a safe initial index for an empty question set', () => {
        expect(getQuestionStep({ questions: [], answers: {} })).toBe(0);
    });

    it('resumes a selected option that still requires follow-up after all answers are present', () => {
        const followUpQuestions = questions.map((question, index) =>
            index === 1
                ? {
                      ...question,
                      options: [
                          {
                              value: 'CHECK',
                              label: '추가 확인',
                              requiresFollowUp: true,
                          },
                      ],
                  }
                : question,
        );
        expect(
            getQuestionStep({
                questions: followUpQuestions,
                answers: { first: 'YES', second: 'CHECK', third: 'YES' },
            }),
        ).toBe(1);
    });
});
