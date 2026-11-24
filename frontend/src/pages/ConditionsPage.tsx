import { showQuestionEvidence } from '../features/housing/conditionOverview';
import { noticeListTitle } from '../features/housing/noticeDisplay';
import { QuestionSteps } from '../features/preparation/QuestionSteps';
import { ConditionResult } from '../features/preparation/ConditionResult';
import {
    questionKind,
    validSubmittedAnswer,
    answerableQuestions,
    needsFollowUp,
} from '../features/housing/questions';
import { QuestionInput } from '../features/preparation/QuestionInput';
import { bindClasses } from '../shared/styles/classes';
import { useEffect, useRef, useState } from 'react';
import { useUnsavedChanges } from '../shared/ui/useUnsavedChanges';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import type { Answer } from '../features/housing/types';
import { Button, Heading, State } from '../shared/ui/ui';
import { handleDisclosureCardClick } from '../shared/ui/handleDisclosureCardClick';
import { EvidenceLink } from '../features/housing/EvidenceLink';
import { QuestionPrompt } from '../features/preparation/QuestionPrompt';

import { Icon } from '../shared/ui/Icon';
import { GuidanceRefresh } from '../features/preparation/PreparationNav';
import {
    usePreparation,
    useSaved,
} from '../features/preparation/usePreparation';
import questionsClasses from '../features/preparation/Conditions.module.css';
import { getQuestionStep } from '../features/preparation/questionNavigation';
import { answerConflictMessages } from '../features/housing/answerDependencies';
import { updateApplicantAnswer } from '../features/preparation/applicantQuestion';
import { applicantInputQuestions } from '../features/housing/preparationApplicability';

const cx = bindClasses(questionsClasses);

export function ConditionsPage({
    mode,
}: {
    mode: 'questions' | 'tab' | 'result';
}) {
    const edit = mode === 'questions';
    const { state } = useLocation();
    const q = usePreparation();
    const p = q.data;
    const documentQuestionIds = Object.values(p?.documentConditions ?? {});
    const allQuestions = answerableQuestions(
        p ? applicantInputQuestions(p) : [],
        documentQuestionIds,
    );
    const saved = useSaved();
    const navigate = useNavigate();
    const [answers, setAnswers] = useState<Record<string, Answer>>({});
    const [params, setParams] = useSearchParams();
    const remainingMode = params.get('review') === 'remaining';
    const [reviewSnapshot, setReviewSnapshot] = useState<{
        id: string;
        ids: string[];
    } | null>(null);
    let snapshot = reviewSnapshot;
    if (p && remainingMode && snapshot?.id !== p.id) {
        snapshot = {
            id: p.id,
            ids: allQuestions
                .filter((item) =>
                    needsFollowUp(
                        item,
                        p.answers[item.id],
                        p.answers,
                        p.questions,
                    ),
                )
                .map((item) => item.id),
        };
        setReviewSnapshot(snapshot);
    }
    const questions = remainingMode
        ? allQuestions.filter((item) => snapshot?.ids.includes(item.id))
        : allQuestions;
    const step = p
        ? getQuestionStep(
              { questions, answers: p.answers },
              params.get('question'),
              p.questions,
          )
        : 0;
    const question = questions[step];
    const questionId = question?.id;
    useEffect(() => {
        if (!edit || !questionId || params.get('question') === questionId)
            return;
        // Keep the entry question stable when saved answers are refetched.
        const next = new URLSearchParams(params);
        next.set('question', questionId);
        setParams(next, { replace: true, state });
    }, [edit, questionId, params, setParams, state]);
    function goToQuestion(id: string) {
        const next = new URLSearchParams(params);
        next.set('question', id);
        setParams(next, { replace: true, state });
    }
    const submitting = useRef(false);
    useUnsavedChanges(Object.keys(answers).length > 0);
    const save = useMutation({
        mutationFn: ({ id, value }: { id: string; value: Answer }) =>
            api.answers(
                p!.id,
                p!.revision,
                updateApplicantAnswer(p!.questions, p!.answers, id, value),
            ),
        onSuccess: (next, { id, value }) => {
            saved(next);
            setAnswers((drafts) => {
                const remaining = { ...drafts };
                delete remaining[id];
                const rebased = updateApplicantAnswer(
                    p!.questions,
                    { ...p!.answers, ...remaining },
                    id,
                    value,
                );
                return Object.fromEntries(
                    Object.keys(remaining).map((key) => [key, rebased[key]]),
                );
            });
            const answerable = answerableQuestions(
                applicantInputQuestions(next),
                Object.values(next.documentConditions ?? {}),
            );
            const remainingIds = new Set(reviewSnapshot?.ids);
            if (remainingMode) {
                for (const item of answerable) {
                    if (
                        !allQuestions.some(
                            (previous) => previous.id === item.id,
                        ) ||
                        (needsFollowUp(
                            item,
                            next.answers[item.id],
                            next.answers,
                            next.questions,
                        ) &&
                            !needsFollowUp(
                                item,
                                p!.answers[item.id],
                                p!.answers,
                                p!.questions,
                            ))
                    )
                        remainingIds.add(item.id);
                }
                setReviewSnapshot({ id: next.id, ids: [...remainingIds] });
            }
            const nextQuestions = answerable.filter(
                (item) => !remainingMode || remainingIds.has(item.id),
            );
            const currentIndex = nextQuestions.findIndex(
                (item) => item.id === id,
            );
            if (currentIndex < nextQuestions.length - 1)
                goToQuestion(nextQuestions[currentIndex + 1].id);
            else
                navigate('/preparations/' + next.id + '/conditions/result', {
                    state,
                });
        },
        onSettled: () => {
            submitting.current = false;
        },
    });
    function submit(nextAnswers: Record<string, Answer>, automatic = false) {
        if (submitting.current || !p || p.guideState !== 'AVAILABLE') return;
        const current = questions[step];
        if (
            !current ||
            !validSubmittedAnswer(
                current,
                nextAnswers[current.id] ?? p.answers[current.id],
            )
        )
            return;
        const combined = { ...p.answers, ...nextAnswers };
        if (
            automatic &&
            answerConflictMessages(
                current,
                combined[current.id],
                combined,
                p.questions,
            ).length
        )
            return;
        submitting.current = true;
        save.mutate({
            id: current.id,
            value: nextAnswers[current.id] ?? p.answers[current.id],
        });
    }
    return (
        <div
            className={cx(edit ? 'condition-page' : 'result-page')}
            data-question-page={edit || undefined}
            data-preparation-panel={mode === 'result' || undefined}
        >
            {edit && (
                <Heading
                    title="내 상황에 맞는 준비 안내"
                    backTo={p ? '/preparations/' + p.id : '/preparations'}
                >
                    {p && noticeListTitle(p.title)}
                </Heading>
            )}
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <GuidanceRefresh
                    p={p}
                    onRefreshed={() => {
                        setAnswers({});
                        setParams({}, { replace: true, state });
                    }}
                />
            )}
            {p &&
                (edit && question ? (
                    <div className={cx('condition-layout')}>
                        <QuestionSteps
                            questions={questions}
                            step={step}
                            answers={{ ...p.answers, ...answers }}
                            dependencyQuestions={p.questions}
                        />
                        <section
                            className={cx('panel question-panel')}
                            onClick={handleDisclosureCardClick}
                        >
                            <fieldset key={question.id}>
                                <QuestionPrompt
                                    question={question}
                                    reviewIssues={p.reviewIssues}
                                    answers={{ ...p.answers, ...answers }}
                                    questions={p.questions}
                                />
                                <QuestionInput
                                    question={question}
                                    answers={{ ...p.answers, ...answers }}
                                    questions={p.questions}
                                    value={
                                        answers[question.id] ??
                                        p.answers[question.id]
                                    }
                                    disabled={
                                        save.isPending ||
                                        p.guideState !== 'AVAILABLE'
                                    }
                                    onRepeatChoice={() => submit(answers, true)}
                                    onChange={(value) => {
                                        const next = {
                                            ...answers,
                                            [question.id]: value,
                                        };
                                        setAnswers(next);
                                        if (questionKind(question) === 'SINGLE')
                                            submit(next, true);
                                    }}
                                />
                            </fieldset>
                            {showQuestionEvidence(question) && (
                                <div className={cx('question-evidence')}>
                                    <Icon name="file" />
                                    <EvidenceLink
                                        evidence={question.evidence}
                                    />
                                </div>
                            )}
                            {p.guideState !== 'AVAILABLE' && (
                                <p role="alert">
                                    안내 검토 중으로 저장이 보류됩니다.
                                </p>
                            )}
                            {save.error && (
                                <p role="alert">{save.error.message}</p>
                            )}
                            <div className={cx('question-buttons')}>
                                <Button
                                    className="secondary"
                                    disabled={step === 0 || save.isPending}
                                    onClick={() =>
                                        goToQuestion(questions[step - 1].id)
                                    }
                                >
                                    이전
                                </Button>
                                <Button
                                    disabled={
                                        save.isPending ||
                                        !validSubmittedAnswer(
                                            question,
                                            answers[question.id] ??
                                                p.answers[question.id],
                                        ) ||
                                        p.guideState !== 'AVAILABLE'
                                    }
                                    onClick={() => submit(answers)}
                                >
                                    {save.isPending ? '저장 중…' : '다음'}
                                </Button>
                            </div>
                        </section>
                    </div>
                ) : (
                    <ConditionResult p={p} inTab={mode === 'tab'} />
                ))}
        </div>
    );
}
