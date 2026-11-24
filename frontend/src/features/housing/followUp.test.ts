import { describe, expect, it } from 'vitest';
import {
    emptyFollowUp,
    followUpSchedules,
    followUpState,
    validatedFollowUp,
} from './followUp';
import type { Preparation } from './types';

describe('공식 후속 일정과 개인 기록 구분', () => {
    it('서류대상 발표·예비입주자 발표를 서류마감·입주일로 바꾸지 않고 없는 날짜를 생성하지 않는다', () => {
        const p: Preparation = {
            id: 'prep',
            noticeId: 'notice',
            title: '테스트 공고',
            version: 1,
            revision: 1,
            guideState: 'AVAILABLE',
            progress: 0,
            houseStage: 0,
            warnings: [],
            nextAction: { label: '', reason: '', path: '' },
            questions: [],
            answers: {},
            documents: [],
            schedules: [
                {
                    id: 'screening',
                    title: '서류 제출 대상자 발표',
                    date: '2026-12-01',
                },
                { id: 'submit', title: '서류 제출 마감', date: '2026-12-10' },
                {
                    id: 'result',
                    title: '예비입주자 발표 · 개별 안내',
                    date: '',
                    timingDescription: '개별 연락',
                },
                {
                    id: 'contract',
                    title: '동·호 지정 및 계약',
                    date: '2026-12-15',
                },
                { id: 'move', title: '입주 지정 기간', date: '' },
            ].map((event) => ({
                ...event,
                recorded: false,
                evidence: {
                    label: '공식 근거',
                    url: null,
                    checkedAt: '2026-11-23',
                },
            })),
        };
        const before = structuredClone(p);
        const steps = followUpSchedules(p);
        expect(steps.result.map((x) => x.id)).toEqual(['screening', 'result']);
        expect(steps.documentsSubmitted.map((x) => x.id)).toEqual(['submit']);
        expect(steps.contractCompleted.map((x) => x.id)).toEqual(['contract']);
        expect(steps.movedIn.map((x) => x.id)).toEqual(['move']);
        expect(steps.movedIn[0].date).toBe('');
        expect(p).toEqual(before);
    });
    it('예비 순번·입주 완료·미선정의 표시와 별도 기록을 유지한다', () => {
        expect(
            followUpState({
                ...emptyFollowUp,
                result: 'WAITLIST',
                waitlistNumber: 12,
            }).label,
        ).toBe('예비 대기 · 12번');
        expect(
            followUpState({
                ...emptyFollowUp,
                result: 'SELECTED',
                movedIn: true,
            }).label,
        ).toBe('입주 완료');
        expect(
            followUpState({ ...emptyFollowUp, result: 'NOT_SELECTED' }).label,
        ).toBe('미선정');
        expect(
            validatedFollowUp({ ...emptyFollowUp, contractCompleted: true }),
        ).toMatchObject({ result: 'WAITING', contractCompleted: true });
    });
    it('예비가 아닌 순번, 잘못된 순번과 상태는 저장하지 않는다', () => {
        for (const invalid of [
            null,
            { ...emptyFollowUp, waitlistNumber: 2 },
            { ...emptyFollowUp, result: 'WAITLIST', waitlistNumber: 0 },
            { ...emptyFollowUp, result: 'WAITLIST', waitlistNumber: 1.5 },
            { ...emptyFollowUp, result: 'WAITLIST', waitlistNumber: 1000000 },
            { ...emptyFollowUp, movedIn: 'true' },
        ])
            expect(() => validatedFollowUp(invalid)).toThrow();
    });
});
