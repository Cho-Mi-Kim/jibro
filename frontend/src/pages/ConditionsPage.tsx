import { QuestionSteps } from '../features/preparation/QuestionSteps';
import { ConditionResult } from '../features/preparation/ConditionResult';
import { answerOptions } from '../features/housing/questions';
import { cx } from '../shared/styles/classes';
import { useRef, useState } from 'react';
import { useUnsavedChanges } from '../shared/ui/useUnsavedChanges';
import { useLocation, useNavigate, useSearchParams } from 'react-router';
import { useMutation } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import type { Answer } from '../features/housing/types';
import { Button, EvidenceLink, Heading, State } from '../shared/ui/ui';

import { Icon } from '../shared/ui/Icon';
import { GuidanceRefresh } from '../features/preparation/PreparationNav';
import {
    usePreparation,
    useSaved,
} from '../features/preparation/usePreparation';
export function ConditionsPage({
    mode,
}: {
    mode: 'questions' | 'tab' | 'result';
}) {
    const edit = mode === 'questions';
    const { state } = useLocation();
    const q = usePreparation();
    const p = q.data;
    const saved = useSaved();
    const navigate = useNavigate();
    const [answers, setAnswers] = useState<Record<string, Answer>>({});
    const [params, setParams] = useSearchParams();
    const step = Math.max(
        0,
        p?.questions.findIndex(
            (question) => question.id === params.get('question'),
        ) ?? 0,
    );
    function goToQuestion(id: string) {
        setParams({ question: id }, { replace: true, state });
    }
    const [dirty, setDirty] = useState(false);
    const submitting = useRef(false);
    useUnsavedChanges(dirty);
    const save = useMutation({
        mutationFn: (nextAnswers: Record<string, Answer>) =>
            api.answers(p!.id, p!.revision, { ...p!.answers, ...nextAnswers }),
        onSuccess: (next) => {
            saved(next);
            setDirty(false);
            setAnswers({});
            if (step < next.questions.length - 1)
                goToQuestion(next.questions[step + 1].id);
            else
                navigate('/preparations/' + next.id + '/conditions/result', {
                    state,
                });
        },
        onSettled: () => {
            submitting.current = false;
        },
    });
    function submit(nextAnswers: Record<string, Answer>) {
        if (submitting.current || !p || p.guideState !== 'AVAILABLE') return;
        submitting.current = true;
        save.mutate(nextAnswers);
    }
    const question = p?.questions[step];
    return (
        <div className={cx(edit ? 'condition-page' : 'result-page')}>
            {edit && (
                <Heading
                    title="조건 확인"
                    backTo={p ? '/preparations/' + p.id : '/preparations'}
                >
                    {p ? p.title + ' · 안내 v' + p.version : undefined}
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
                        setDirty(false);
                    }}
                />
            )}
            {p &&
                (edit && question ? (
                    <div className={cx('condition-layout')}>
                        <QuestionSteps questions={p.questions} step={step} />
                        <section className={cx('panel question-panel')}>
                            <fieldset key={question.id}>
                                <legend aria-live="polite">
                                    {question.title}
                                </legend>
                                {answerOptions(question).map(
                                    ({ value: a, label }) => (
                                        <label className={cx('choice')} key={a}>
                                            <input
                                                type="radio"
                                                name={question.id}
                                                checked={
                                                    (answers[question.id] ??
                                                        p.answers[
                                                            question.id
                                                        ]) === a
                                                }
                                                disabled={
                                                    save.isPending ||
                                                    p.guideState !== 'AVAILABLE'
                                                }
                                                onClick={() => {
                                                    if (
                                                        (answers[question.id] ??
                                                            p.answers[
                                                                question.id
                                                            ]) === a
                                                    )
                                                        submit(answers);
                                                }}
                                                onChange={() => {
                                                    const nextAnswers = {
                                                        ...answers,
                                                        [question.id]: a,
                                                    };
                                                    setAnswers(nextAnswers);
                                                    setDirty(true);
                                                    submit(nextAnswers);
                                                }}
                                            />
                                            {label}
                                        </label>
                                    ),
                                )}
                            </fieldset>
                            <div className={cx('question-evidence')}>
                                <Icon name="file" />
                                <EvidenceLink evidence={question.evidence} />
                            </div>
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
                                        goToQuestion(p.questions[step - 1].id)
                                    }
                                >
                                    이전
                                </Button>
                                <Button
                                    disabled={
                                        save.isPending ||
                                        !(
                                            answers[question.id] ??
                                            p.answers[question.id]
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
