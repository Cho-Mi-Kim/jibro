import { ApiError } from '../../shared/api/client';
import { canRecordSchedule } from './schedules';
import { documentNeedsReview } from './reviewIssueSelectors';
import {
    answerOptions,
    validAnswer,
    validSubmittedAnswer,
    sameAnswer,
    needsFollowUp,
    applicantQuestions,
} from './questions';
import type { Answer, DocumentItem, Preparation, Question } from './types';
import {
    visiblePreparationQuestions,
    visiblePreparationDocuments,
    preparationScheduleSelection,
} from './preparationApplicability';
import {
    answerConflictMessages,
    dependentDocumentNecessity,
} from './answerDependencies';

export interface DemoDocumentRule {
    questionId: string;
    answerValues: readonly string[];
}

export function demoDocumentNecessity(
    rule: DemoDocumentRule | undefined,
    answers: Record<string, Answer>,
    questions: readonly Question[],
): DocumentItem['necessity'] {
    if (!rule) return 'REQUIRED';
    const answer = answers[rule.questionId] ?? 'UNKNOWN';
    if (answer === 'UNKNOWN') return 'UNKNOWN';
    if (
        (Array.isArray(answer) ? answer : [answer]).some(
            (value) =>
                typeof value === 'string' && rule.answerValues.includes(value),
        )
    )
        return 'CONDITIONAL';
    const question = questions.find((q) => q.id === rule.questionId);
    return !question || needsFollowUp(question, answer)
        ? 'UNKNOWN'
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
                !(sameAnswer(preparation.answers[id], value)
                    ? validAnswer(question, value)
                    : validSubmittedAnswer(question, value))
            );
        })
    )
        throw new ApiError(400, '질문과 선택지를 다시 확인해 주세요.');
    return {
        ...preparation,
        answers: Object.fromEntries(
            Object.entries(answers).map(([id, value]) => [
                id,
                Array.isArray(value)
                    ? answerOptions(
                          preparation.questions.find((q) => q.id === id)!,
                      )
                          .map((option) => option.value)
                          .filter((option) => value.includes(option))
                    : value,
            ]),
        ),
        documents: preparation.documents.map((document) => {
            const necessity = documentNeedsReview(
                document,
                preparation.reviewIssues,
            )
                ? 'UNKNOWN'
                : dependentDocumentNecessity(
                      demoDocumentNecessity(
                          rules[document.id],
                          answers,
                          preparation.questions,
                      ),
                      document.id,
                      preparation.questions,
                      answers,
                  );
            if (necessity === document.necessity) return document;
            return {
                ...document,
                necessity,
                state: document.state === 'READY' ? 'RECHECK' : 'UNCHECKED',
                stateBeforeReady: undefined,
            };
        }),
    };
}

// The static preview has no Java server; these rules implement its preparation contract.
export function assessDemoPreparation(p: Preparation): Preparation {
    const questions = applicantQuestions(
        visiblePreparationQuestions(p),
        Object.values(p.documentConditions ?? {}),
    );
    const known = questions.filter(
        (q) => !needsFollowUp(q, p.answers[q.id], p.answers, p.questions),
    ).length;
    const documents = visiblePreparationDocuments(p);
    const ready = documents.filter(
        (d) =>
            d.state === 'READY' &&
            d.necessity !== 'UNKNOWN' &&
            !documentNeedsReview(d, p.reviewIssues),
    ).length;
    const schedules = preparationScheduleSelection(
        p,
        p.schedules,
    ).visible.filter((event) => canRecordSchedule(event, p.schedules));
    const recorded = schedules.filter((s) => s.recorded).length;
    const questionWeight = 30;
    const documentWeight = 50;
    const scheduleWeight = 20;
    const rounded = Math.round(
        (questions.length
            ? known / questions.length
            : p.questions.length
              ? 1
              : 0) *
            questionWeight +
            (documents.length ? ready / documents.length : 0) * documentWeight +
            (schedules.length
                ? recorded / schedules.length
                : p.schedules.length
                  ? 1
                  : 0) *
                scheduleWeight,
    );
    const progress =
        !!p.reviewIssues?.length ||
        known < questions.length ||
        ready < documents.length ||
        recorded < schedules.length
            ? Math.min(99, rounded)
            : rounded;
    const warnings = questions
        .filter(
            (q) =>
                !q.reviewRequired &&
                needsFollowUp(q, p.answers[q.id], p.answers, p.questions),
        )
        .map((q) => q.title);
    const conflicts = [
        ...new Set(
            p.questions.flatMap((q) =>
                answerConflictMessages(
                    q,
                    p.answers[q.id],
                    p.answers,
                    p.questions,
                ),
            ),
        ),
    ];
    warnings.push(...conflicts);
    warnings.push(
        ...(p.reviewIssues ?? []).map(
            (issue) => '공식 확인 필요 · ' + issue.title,
        ),
    );
    const recheck = documents.find(
        (d) => d.state === 'RECHECK' && !documentNeedsReview(d, p.reviewIssues),
    );
    const unready = documents.find(
        (d) =>
            !documentNeedsReview(d, p.reviewIssues) &&
            (d.state !== 'READY' || d.necessity === 'UNKNOWN'),
    );
    const answerNeeded = questions.some(
        (q) =>
            !q.reviewRequired &&
            needsFollowUp(q, p.answers[q.id], p.answers, p.questions),
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
    } else if (answerNeeded) {
        nextAction = {
            label: '확인하지 못한 조건 살펴보기',
            reason: conflicts.length
                ? '서로 다른 답변을 다시 확인해 주세요.'
                : '모름·미입력 항목이 남아 있어요.',
            path: prefix + '/conditions/edit',
        };
    } else if (unready) {
        nextAction = {
            label: unready.title + ' 준비하기',
            reason: '아직 준비가 끝나지 않은 서류예요.',
            path: prefix + '/documents',
        };
    } else if (p.reviewIssues?.length) {
        nextAction = {
            label: '공식 확인이 필요한 항목 보기',
            reason: '기관 자료의 확인 필요 항목은 준비 완료로 계산하지 않아요.',
            path: '/notices/' + p.noticeId,
        };
    } else if (progress === 100) {
        nextAction = {
            label: '준비 기록 완료',
            reason: '내 답변에 해당하는 조건·서류·일정을 모두 기록했어요. 결과와 후속 안내는 공식 공고에서 확인해 주세요.',
            path: prefix + '/schedule',
            completed: true,
        };
    } else {
        nextAction = {
            label: '신청 일정 확인하기',
            reason: '신청·제출을 마친 일정은 일정 탭에서 완료로 표시해 주세요.',
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
