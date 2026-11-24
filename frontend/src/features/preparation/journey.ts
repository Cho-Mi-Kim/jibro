import type { Preparation } from '../housing/types';
import { showFollowUp } from '../housing/followUp';
import { needsFollowUp, applicantQuestions } from '../housing/questions';
import {
    visiblePreparationQuestions,
    visiblePreparationDocuments,
} from '../housing/preparationApplicability';

export function journeyStage(p: Preparation) {
    if (showFollowUp(p))
        return p.followUp && p.followUp.result !== 'WAITING' ? 5 : 4;
    const questions = applicantQuestions(
        visiblePreparationQuestions(p),
        Object.values(p.documentConditions ?? {}),
    );
    const conditions =
        p.questions.length > 0 &&
        questions.every(
            (q) => !needsFollowUp(q, p.answers[q.id], p.answers, p.questions),
        );
    const docs = visiblePreparationDocuments(p);
    const ready =
        docs.length > 0 &&
        docs.every((d) => d.necessity !== 'UNKNOWN' && d.state === 'READY');
    return !conditions ? 1 : !ready ? 2 : 3;
}
export function journeySteps(p: Preparation) {
    const base = '/preparations/' + p.id;
    return [
        {
            label: '공고 확인',
            path: '/notices/' + p.noticeId,
            description:
                '열람한 공고의 기본 정보와 공식 근거를 다시 확인하세요.',
        },
        {
            label: '조건 확인',
            path: base + '/conditions',
            description: '저장한 답변과 추가로 확인할 조건을 살펴보세요.',
        },
        {
            label: '서류 준비',
            path: base + '/documents',
            description: '이 공고의 준비 대상 서류와 발급 방법을 확인하세요.',
        },
        {
            label: '공식 신청',
            path: base + '?application=record',
            description: '운영기관에서 신청하고, 신청 여부를 따로 기록하세요.',
        },
        {
            label: '후속 절차',
            path: showFollowUp(p)
                ? base + '/follow-up'
                : base + '?application=record',
            description: '결과·서류 제출·계약·입주 상황을 기록하세요.',
        },
    ];
}
