import { describe, expect, it } from 'vitest';
import type { FollowUpRecord, Preparation } from '../housing/types';
import { emptyFollowUp, showFollowUp } from '../housing/followUp';
import { journeyStage, journeySteps } from './journey';

const p: Preparation = {
    id: 'p',
    noticeId: 'n',
    title: '공고',
    version: 1,
    revision: 1,
    guideState: 'AVAILABLE',
    questions: [],
    answers: {},
    documents: [],
    schedules: [
        {
            id: 'apply',
            title: '신청 접수 마감',
            date: '2026-12-01',
            recorded: false,
            evidence: {
                label: '공식 일정',
                url: null,
                checkedAt: '2026-11-23',
            },
        },
    ],
    progress: 100,
    houseStage: 5,
    warnings: [],
    nextAction: { label: '', reason: '', path: '' },
};

describe('신청 기록과 선정 결과에 따른 준비 단계', () => {
    it('준비도 100%나 기존 결과만으로 신청 후 화면을 열지 않는다', () => {
        const before = {
            ...p,
            followUp: { ...emptyFollowUp, result: 'SELECTED' as const },
        };
        expect(showFollowUp(before)).toBe(false);
        expect(journeyStage(before)).toBeLessThan(4);
        expect(journeySteps(before)[4].path).toBe(
            '/preparations/p?application=record',
        );
        expect(showFollowUp({ ...before, schedules: [] })).toBe(false);
    });
    it.each([
        undefined,
        'WAITING',
        'SELECTED',
        'WAITLIST',
        'NOT_SELECTED',
    ] as const)(
        '신청 완료 후 저장된 결과 %s에 따라 후속 단계만 전환하고 준비도와 원본은 유지한다',
        (result) => {
            const record: Preparation = {
                ...p,
                schedules: p.schedules.map((s) => ({ ...s, recorded: true })),
                followUp: result
                    ? {
                          ...emptyFollowUp,
                          result: result as FollowUpRecord['result'],
                      }
                    : undefined,
            };
            const original = structuredClone(record);
            expect(showFollowUp(record)).toBe(true);
            expect(journeyStage(record)).toBe(
                !result || result === 'WAITING' ? 4 : 5,
            );
            expect(journeySteps(record)[4].path).toBe(
                '/preparations/p/follow-up',
            );
            expect(record).toEqual(original);
        },
    );
});
