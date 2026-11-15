import {
    answerLabel,
    answerOptions,
    inputDescription,
} from '../../housing/questions';
import type { DocumentState, Preparation } from '../../housing/types';
import { EvidenceLink } from '../../housing/EvidenceLink';
import { DocumentSummaryFacts } from '../../housing/DocumentSummaryFacts';
import { documentSummary } from '../../housing/documentSummary';
import { ConditionSummary } from '../../housing/ConditionSummary';
import { questionReviewIssues } from '../../housing/reviewIssueSelectors';
import styles from './PreparationHistory.module.css';

const documentStates: Record<DocumentState, string> = {
    UNCHECKED: '미확인',
    NEEDED: '발급 필요',
    IN_PROGRESS: '준비 중',
    READY: '준비 완료',
    RECHECK: '재확인',
};
const necessities: Record<string, string> = {
    REQUIRED: '공통 필수',
    CONDITIONAL: '해당 조건부',
    UNKNOWN: '해당 여부 확인 필요',
    NOT_APPLICABLE: '당시 해당 없음',
};

export function HistorySnapshot({
    preparation: p,
}: {
    preparation: Preparation;
}) {
    return (
        <div className={styles.snapshot}>
            <section className={styles.card} aria-labelledby="history-answers">
                <h2 id="history-answers">당시 질문과 답변</h2>
                {p.questions.length === 0 && <p>저장된 질문이 없습니다.</p>}
                <ol className={styles.items}>
                    {p.questions.map((question) => {
                        const options = answerOptions(question);
                        const answer = p.answers[question.id];
                        return (
                            <li key={question.id}>
                                <ConditionSummary
                                    question={question}
                                    heading="h3"
                                    issues={questionReviewIssues(
                                        p.reviewIssues,
                                        question.id,
                                    )}
                                    historicalChoices={
                                        <div className={styles.evidence}>
                                            <p>{inputDescription(question)}</p>
                                            <ul>
                                                {options.map((option) => (
                                                    <li key={option.value}>
                                                        {option.label}
                                                        {(
                                                            Array.isArray(
                                                                answer,
                                                            )
                                                                ? answer.includes(
                                                                      option.value,
                                                                  )
                                                                : option.value ===
                                                                  answer
                                                        )
                                                            ? ' · 선택한 답변'
                                                            : ''}
                                                    </li>
                                                ))}
                                            </ul>
                                        </div>
                                    }
                                >
                                    <p className={styles.value}>
                                        {answer === undefined
                                            ? '미응답'
                                            : answerLabel(question, answer)}
                                    </p>
                                </ConditionSummary>
                            </li>
                        );
                    })}
                </ol>
            </section>
            <section
                className={styles.card}
                aria-labelledby="history-documents"
            >
                <h2 id="history-documents">당시 서류와 메모</h2>
                {p.documents.length === 0 && <p>저장된 서류가 없습니다.</p>}
                <ul className={styles.items}>
                    {p.documents.map((document) => {
                        const summary = documentSummary(document, p, {
                            historical: true,
                        });
                        return (
                            <li key={document.id}>
                                <h3>{document.title}</h3>
                                <p className={styles.value}>
                                    {documentStates[document.state]} ·{' '}
                                    {necessities[document.necessity] ??
                                        document.necessity}
                                </p>
                                <DocumentSummaryFacts summary={summary} />
                                <dl className={styles.facts}>
                                    <dt>내 메모</dt>
                                    <dd className={styles.note}>
                                        {document.note || '저장된 메모 없음'}
                                    </dd>
                                </dl>
                                <details className={styles.evidence}>
                                    <summary>당시 발급 안내·근거 보기</summary>
                                    {summary.criteria.length > 2 && (
                                        <p>
                                            제출 대상 ·{' '}
                                            {summary.criteria.join(' 또는 ')}
                                        </p>
                                    )}
                                    {document.issuer && (
                                        <p>발급처 · {document.issuer}</p>
                                    )}
                                    {document.method && (
                                        <p>{document.method}</p>
                                    )}
                                    <EvidenceLink
                                        evidence={document.evidence}
                                        checkedAtOnNewLine
                                    />
                                </details>
                            </li>
                        );
                    })}
                </ul>
            </section>
            <section
                className={styles.card}
                aria-labelledby="history-schedules"
            >
                <h2 id="history-schedules">당시 일정과 신청 기록</h2>
                {p.schedules.length === 0 && <p>저장된 일정이 없습니다.</p>}
                <ul className={styles.items}>
                    {p.schedules.map((schedule) => (
                        <li key={schedule.id}>
                            <h3>{schedule.title}</h3>
                            <p>{schedule.date || '날짜 정보 없음'}</p>
                            <p className={styles.value}>
                                {schedule.recorded
                                    ? '완료로 기록함'
                                    : '완료 기록 없음'}
                            </p>
                            <details className={styles.evidence}>
                                <summary>당시 근거</summary>
                                <EvidenceLink
                                    evidence={schedule.evidence}
                                    checkedAtOnNewLine
                                />
                            </details>
                        </li>
                    ))}
                </ul>
            </section>
        </div>
    );
}
