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
import { QuestionInput } from './QuestionInput';
import { QuestionCriteria } from './QuestionCriteria';
import { QuestionSteps } from './QuestionSteps';
import {
    oneTimeCheckApi,
    type OneTimeCheckResult as Result,
} from './oneTimeCheckApi';
import { OneTimeCheckResult } from './OneTimeCheckResult';
import styles from './OneTimeCheck.module.css';

export function OneTimeCheckSession({ notice }: { notice: Notice }) {
    const questions = answerableQuestions(notice.questions);
    const [answers, setAnswers] = useState<Record<string, Answer>>({});
    const [step, setStep] = useState(0);
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
        setStep(0);
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
            setStep(0);
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
    async function advance(next: Record<string, Answer>) {
        const question = questions[step];
        if (
            busy.current ||
            (question && !validSubmittedAnswer(question, next[question.id]))
        )
            return;
        if (step < questions.length - 1) {
            setStep(step + 1);
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
        <div className={styles.content}>
            <p className={styles.privacy}>
                계정에 저장하지 않아요. 확인을 위해 서버에서 잠시 처리하며,
                화면을 나가거나 새로고침하면 답변과 결과가 사라집니다.
            </p>
            {result ? (
                <OneTimeCheckResult
                    result={result}
                    templates={notice.documentTemplates}
                    onEdit={() => setResult(null)}
                    onClear={clear}
                />
            ) : (
                <>
                    <PartialGuidanceNotice issues={notice.reviewIssues} />
                    {question && (
                        <QuestionSteps questions={questions} step={step} />
                    )}
                    <Card>
                        {question ? (
                            <fieldset
                                className={styles.questions}
                                key={question.id}
                            >
                                <legend aria-live="polite">
                                    {question.title}
                                </legend>
                                <QuestionCriteria
                                    question={question}
                                    reviewIssues={notice.reviewIssues}
                                />
                                <QuestionInput
                                    question={question}
                                    value={answers[question.id]}
                                    disabled={pending}
                                    onRepeatChoice={() => void advance(answers)}
                                    onChange={(value) => {
                                        const next = {
                                            ...answers,
                                            [question.id]: value,
                                        };
                                        setAnswers(next);
                                        if (questionKind(question) === 'SINGLE')
                                            void advance(next);
                                    }}
                                />
                                <EvidenceLink evidence={question.evidence} />
                            </fieldset>
                        ) : (
                            <p>이 공고의 공통 서류 안내를 확인할 수 있어요.</p>
                        )}
                        {error && <p role="alert">{error}</p>}
                        <div className={styles.actions}>
                            <Button
                                className="secondary"
                                disabled={!step || pending}
                                onClick={() => setStep(step - 1)}
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
