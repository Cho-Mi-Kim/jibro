import { ApiError } from '../../shared/api/client';
import { answerOptions, needsFollowUp } from './questions';
import type { Answer, DocumentItem, Preparation } from './types';

export interface DemoDocumentRule {
    questionId: string;
    answerValues: readonly string[];
}

export function demoDocumentNecessity(
    rule: DemoDocumentRule | undefined,
    answers: Record<string, Answer>,
): DocumentItem['necessity'] {
    if (!rule) return 'REQUIRED';
    const answer = answers[rule.questionId] ?? 'UNKNOWN';
    return answer === 'UNKNOWN'
        ? 'UNKNOWN'
        : rule.answerValues.includes(answer)
          ? 'CONDITIONAL'
          : 'NOT_APPLICABLE';
}

export function answerDemoPreparation(
    preparation: Preparation,
    answers: Record<string, Answer>,
    rules: Readonly<Record<string, DemoDocumentRule>>,
): Preparation {
    if (
        !answers ||
        typeof answers !== 'object' ||
        Array.isArray(answers) ||
        Object.entries(answers).some(([id, value]) => {
            const question = preparation.questions.find((q) => q.id === id);
            return (
                !question ||
                !answerOptions(question).some((o) => o.value === value)
            );
        })
    )
        throw new ApiError(400, '질문과 선택지를 다시 확인해 주세요.');
    return {
        ...preparation,
        answers: { ...answers },
        documents: preparation.documents.map((document) => {
            const necessity = demoDocumentNecessity(
                rules[document.id],
                answers,
            );
            if (necessity === document.necessity) return document;
            return {
                ...document,
                necessity,
                state: document.state === 'READY' ? 'RECHECK' : 'UNCHECKED',
            };
        }),
    };
}

// The static preview has no Java server; these rules implement its preparation contract.
export function assessDemoPreparation(p: Preparation): Preparation {
    const known = p.questions.filter(
        (q) => !needsFollowUp(q, p.answers[q.id]),
    ).length;
    const documents = p.documents.filter(
        (d) => d.necessity !== 'NOT_APPLICABLE',
    );
    const ready = documents.filter(
        (d) => d.state === 'READY' && d.necessity !== 'UNKNOWN',
    ).length;
    const recorded = p.schedules.filter((s) => s.recorded).length;
    const questionWeight = 30;
    const documentWeight = 50;
    const scheduleWeight = 20;
    const rounded = Math.round(
        (p.questions.length ? known / p.questions.length : 0) * questionWeight +
            (documents.length ? ready / documents.length : 0) * documentWeight +
            (p.schedules.length ? recorded / p.schedules.length : 0) *
                scheduleWeight,
    );
    const progress =
        known < p.questions.length ||
        ready < documents.length ||
        recorded < p.schedules.length
            ? Math.min(99, rounded)
            : rounded;
    const warnings = p.questions
        .filter((q) => needsFollowUp(q, p.answers[q.id]))
        .map((q) => q.title);
    const recheck = documents.find((d) => d.state === 'RECHECK');
    const unready = documents.find(
        (d) => d.state !== 'READY' || d.necessity === 'UNKNOWN',
    );
    const prefix = '/preparations/' + p.id;
    let nextAction: Preparation['nextAction'];
    if (p.guideState !== 'AVAILABLE') {
        warnings.push(
            '공고 기준이 변경되었거나 안내를 재검수 중입니다. 기존 기록은 보존됩니다.',
        );
        nextAction = {
            label: '공고 변경 내용 확인하기',
            reason: '새 기준 확인 전에는 준비 기록을 수정할 수 없어요.',
            path: '/notices/' + p.noticeId,
        };
    } else if (recheck) {
        nextAction = {
            label: '서류 다시 확인하기',
            reason: '조건 변경으로 재확인이 필요한 서류가 있어요.',
            path: prefix + '/documents',
        };
    } else if (warnings.length) {
        nextAction = {
            label: '확인하지 못한 조건 살펴보기',
            reason: '모름·미입력 항목이 남아 있어요.',
            path: prefix + '/conditions/edit',
        };
    } else if (unready) {
        nextAction = {
            label: unready.title + ' 준비하기',
            reason: '아직 준비가 끝나지 않은 서류예요.',
            path: prefix + '/documents',
        };
    } else {
        nextAction = {
            label: '신청 일정 확인하기',
            reason: '공식 절차와 일정을 확인하세요.',
            path: prefix + '/schedule',
        };
    }
    return {
        ...p,
        progress,
        houseStage:
            progress < 20
                ? 1
                : progress < 40
                  ? 2
                  : progress < 70
                    ? 3
                    : progress < 100
                      ? 4
                      : 5,
        warnings,
        nextAction,
    };
}
