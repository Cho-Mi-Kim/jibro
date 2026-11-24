import { showQuestionEvidence } from '../housing/conditionOverview';
import { useEffect, useRef, useState } from 'react';
import type { Answer, Notice } from '../housing/types';
import {
    answerableQuestions,
    questionKind,
    validSubmittedAnswer,
} from '../housing/questions';
import { PartialGuidanceNotice } from '../housing/ReviewIssues';
import { EvidenceLink } from '../housing/EvidenceLink';
import { Button, Card } from '../../shared/ui/ui';
import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';
import { QuestionInput } from './QuestionInput';
import { QuestionPrompt } from './QuestionPrompt';
import { QuestionSteps } from './QuestionSteps';
import {
    oneTimeCheckApi,
    type OneTimeCheckResult as Result,
} from './oneTimeCheckApi';
import { OneTimeCheckResult } from './OneTimeCheckResult';
import styles from './OneTimeCheck.module.css';
import { answerConflictMessages } from '../housing/answerDependencies';
import { getQuestionStep } from './questionNavigation';
import { updateApplicantAnswer } from './applicantQuestion';
import { applicantInputQuestions } from '../housing/preparationApplicability';

export function OneTimeCheckSession({ notice }: { notice: Notice }) {
    const [answers, setAnswers] = useState<Record<string, Answer>>({});
    const [questionId, setQuestionId] = useState<string | null>(null);
    function questionsFor(nextAnswers: Record<string, Answer>) {
        return answerableQuestions(
            applicantInputQuestions({ ...notice, answers: nextAnswers }),
            (notice.documentTemplates ?? []).flatMap((document) =>
                document.conditionQuestionId
                    ? [document.conditionQuestionId]
                    : [],
            ),
        );
    }
    const questions = questionsFor(answers);
    const step = getQuestionStep(
        { questions, answers },
        questionId ?? questions[0]?.id,
        notice.questions,
    );
    const [result, setResult] = useState<Result | null>(null);
    const [pending, setPending] = useState(false);
    const [error, setError] = useState('');
    const request = useRef({ version: 0 });
    const busy = useRef(false);
    function clear() {
        request.current.version++;
        busy.current = false;
        setAnswers({});
        setResult(null);
        setQuestionId(null);
        setError('');
        setPending(false);
    }
    useEffect(() => {
        const lifetime = request.current;
        // Clear before a page can enter the browser's back/forward cache.
        function discard() {
            lifetime.version++;
            busy.current = false;
            setAnswers({});
            setResult(null);
            setQuestionId(null);
            setError('');
            setPending(false);
        }
        function restore(event: PageTransitionEvent) {
            if (event.persisted) discard();
        }
        window.addEventListener('pagehide', discard);
        window.addEventListener('pageshow', restore);
        return () => {
            lifetime.version++;
            window.removeEventListener('pagehide', discard);
            window.removeEventListener('pageshow', restore);
        };
    }, []);
    async function advance(next: Record<string, Answer>, automatic = false) {
        const question = questions[step];
        if (
            busy.current ||
            (question && !validSubmittedAnswer(question, next[question.id]))
        )
            return;
        if (
            automatic &&
            question &&
            answerConflictMessages(
                question,
                next[question.id],
                next,
                notice.questions,
            ).length
        )
            return;
        const nextQuestions = questionsFor(next);
        const currentIndex = nextQuestions.findIndex(
            (item) => item.id === question?.id,
        );
        if (currentIndex < nextQuestions.length - 1) {
            setQuestionId(nextQuestions[currentIndex + 1].id);
            return;
        }
        busy.current = true;
        setPending(true);
        setError('');
        const currentRequest = ++request.current.version;
        try {
            const evaluated = await oneTimeCheckApi.evaluate(
                notice.id,
                notice.version,
                next,
            );
            if (currentRequest === request.current.version)
                setResult(evaluated);
        } catch (failure) {
            if (currentRequest === request.current.version)
                setError(
                    failure instanceof Error
                        ? failure.message
                        : '확인하지 못했습니다. 다시 시도해 주세요.',
                );
        } finally {
            if (currentRequest === request.current.version) {
                busy.current = false;
                setPending(false);
            }
        }
    }
    const question = questions[step];
    return (
        <div className={styles.content} data-question-page>
            {!result && (
                <p>내 상황을 알려주시면 필요한 서류와 신청 준비를 안내해요.</p>
            )}
            <p className={styles.privacy}>
                계정에 저장하지 않아요. 확인을 위해 서버에서 잠시 처리하며,
                화면을 나가거나 새로고침하면 답변과 결과가 사라집니다.
            </p>
            {result ? (
                <OneTimeCheckResult
                    result={result}
                    templates={notice.documentTemplates}
                    schedules={notice.schedules}
                    guideState={notice.guideState}
                    onEdit={() => {
                        setQuestionId(
                            questions[
                                getQuestionStep(
                                    { questions, answers },
                                    undefined,
                                    notice.questions,
                                )
                            ]?.id ?? null,
                        );
                        setResult(null);
                    }}
                    onClear={clear}
                />
            ) : (
                <>
                    <PartialGuidanceNotice issues={notice.reviewIssues} />
                    {question && (
                        <QuestionSteps
                            questions={questions}
                            step={step}
                            answers={answers}
                            dependencyQuestions={notice.questions}
                        />
                    )}
                    <Card
                        onClick={
                            question ? handleDisclosureCardClick : undefined
                        }
                    >
                        {question ? (
                            <fieldset
                                className={styles.questions}
                                key={question.id}
                            >
                                <QuestionPrompt
                                    question={question}
                                    reviewIssues={notice.reviewIssues}
                                    answers={answers}
                                    questions={notice.questions}
                                />
                                <QuestionInput
                                    question={question}
                                    answers={answers}
                                    questions={notice.questions}
                                    value={answers[question.id]}
                                    disabled={pending}
                                    onRepeatChoice={() =>
                                        void advance(answers, true)
                                    }
                                    onChange={(value) => {
                                        const next = updateApplicantAnswer(
                                            notice.questions,
                                            answers,
                                            question.id,
                                            value,
                                        );
                                        setAnswers(next);
                                        if (questionKind(question) === 'SINGLE')
                                            void advance(next, true);
                                    }}
                                />
                                {showQuestionEvidence(question) && (
                                    <EvidenceLink
                                        evidence={question.evidence}
                                    />
                                )}
                            </fieldset>
                        ) : (
                            <p>이 공고의 공통 서류 안내를 확인할 수 있어요.</p>
                        )}
                        {error && <p role="alert">{error}</p>}
                        <div className={styles.actions}>
                            <Button
                                className="secondary"
                                disabled={!step || pending}
                                onClick={() =>
                                    setQuestionId(questions[step - 1].id)
                                }
                            >
                                이전
                            </Button>
                            <Button
                                disabled={
                                    pending ||
                                    (!!question &&
                                        !validSubmittedAnswer(
                                            question,
                                            answers[question.id],
                                        ))
                                }
                                onClick={() => void advance(answers)}
                            >
                                {pending
                                    ? '확인 중…'
                                    : step < questions.length - 1
                                      ? '다음'
                                      : '결과 확인'}
                            </Button>
                        </div>
                    </Card>
                </>
            )}
        </div>
    );
}
