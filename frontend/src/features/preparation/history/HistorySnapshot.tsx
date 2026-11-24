import {
    applicationRecordSchedule,
    snapshotPreparationScheduleSource,
} from '../../housing/schedules';
import { ScheduleCompletionBadge } from '../ScheduleCompletionBadge';
import { DocumentStateBadge } from '../DocumentStateBadge';
import { ConditionAnswerStatus } from '../ConditionAnswerStatus';
import { AreaReviewIssues } from '../../housing/ReviewIssues';
import {
    answerLabel,
    answerOptions,
    inputDescription,
} from '../../housing/questions';
import type { Preparation } from '../../housing/types';
import { ScheduleSummary, ScheduleCard } from '../../housing/ScheduleCard';
import { EvidenceLink } from '../../housing/EvidenceLink';
import { DocumentCard } from '../../housing/DocumentCard';
import { documentSummary } from '../../housing/documentSummary';
import { ConditionSummary } from '../../housing/ConditionSummary';
import { questionReviewIssues } from '../../housing/reviewIssueSelectors';
import {
    visiblePreparationQuestions,
    visiblePreparationDocuments,
} from '../../housing/preparationApplicability';
import { handleDisclosureCardClick } from '../../../shared/ui/handleDisclosureCardClick';
import { useResultCardScroll } from '../../../shared/ui/useResultCardScroll';
import styles from './PreparationHistory.module.css';
import { FollowUpSummary } from '../FollowUpSummary';

const necessities: Record<string, string> = {
    REQUIRED: '공통 필수',
    CONDITIONAL: '해당 조건부',
    UNKNOWN: '해당 여부 확인 필요',
    NOT_APPLICABLE: '제출 여부 확인 필요',
};

export function HistorySnapshot({
    preparation: p,
}: {
    preparation: Preparation;
}) {
    const questions = visiblePreparationQuestions(p);
    const documents = visiblePreparationDocuments(p);
    const scheduleSource = snapshotPreparationScheduleSource(p);
    const schedules = scheduleSource.schedules;
    const application = applicationRecordSchedule(schedules);
    const snapshotRef = useResultCardScroll('[data-history-snapshot]');
    return (
        <div
            className={styles.snapshot}
            ref={snapshotRef}
            data-history-snapshot
        >
            <p className={styles.description}>
                보관 당시 답변에 맞는 내용과 공통 안내예요. 확인이 필요했던
                항목도 함께 보여드려요.
            </p>
            <FollowUpSummary record={p.followUp} historical />
            <section className={styles.card} aria-labelledby="history-answers">
                <h2 id="history-answers">당시 질문과 답변</h2>
                <AreaReviewIssues area="CONDITION" issues={p.reviewIssues} />
                {questions.length === 0 && (
                    <p>
                        {p.questions.length
                            ? '당시 답변에 해당하는 조건이 없습니다.'
                            : '저장된 질문이 없습니다.'}
                    </p>
                )}
                <ol className={styles.items}>
                    {questions.map((question) => {
                        const options = answerOptions(question);
                        const answer = p.answers[question.id];
                        return (
                            <li
                                key={question.id}
                                data-result-card
                                onClick={handleDisclosureCardClick}
                            >
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
                                    <ConditionAnswerStatus
                                        question={question}
                                        answer={answer}
                                        answers={p.answers}
                                        questions={p.questions}
                                    />
                                    {answer !== undefined && (
                                        <p className={styles.value}>
                                            {answerLabel(question, answer)}
                                        </p>
                                    )}
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
                <AreaReviewIssues area="DOCUMENT" issues={p.reviewIssues} />
                {documents.length === 0 && (
                    <p>
                        {p.documents.length
                            ? '당시 답변에 필요한 서류가 없습니다.'
                            : '저장된 서류가 없습니다.'}
                    </p>
                )}
                <ul className={styles.items}>
                    {documents.map((document) => {
                        const summary = documentSummary(document, p, {
                            historical: true,
                        });
                        return (
                            <li
                                key={document.id}
                                data-result-card
                                onClick={handleDisclosureCardClick}
                            >
                                <DocumentCard
                                    title={document.title}
                                    summary={summary}
                                    heading="h3"
                                >
                                    <p className={styles.value}>
                                        <DocumentStateBadge
                                            state={document.state}
                                        />{' '}
                                        ·{' '}
                                        {necessities[document.necessity] ??
                                            document.necessity}
                                    </p>
                                    <dl className={styles.facts}>
                                        <dt>내 메모</dt>
                                        <dd className={styles.note}>
                                            {document.note ||
                                                '저장된 메모 없음'}
                                        </dd>
                                    </dl>
                                    {!summary.fullMethodShown &&
                                        document.issuer && (
                                            <p>발급처 · {document.issuer}</p>
                                        )}
                                    {!summary.fullMethodShown &&
                                        document.method && (
                                            <p>{document.method}</p>
                                        )}
                                    <EvidenceLink
                                        evidence={document.evidence}
                                    />
                                </DocumentCard>
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
                <AreaReviewIssues area="SCHEDULE" issues={p.reviewIssues} />
                {schedules.length > 0 && (
                    <ScheduleSummary source={scheduleSource} />
                )}
                {schedules.length === 0 && (
                    <p>
                        {p.schedules.length
                            ? '당시 답변에 해당하는 일정이 없습니다.'
                            : '저장된 일정이 없습니다.'}
                    </p>
                )}
                <ul className={styles.items}>
                    {schedules.map((schedule) => (
                        <li
                            key={schedule.id}
                            data-result-card
                            onClick={handleDisclosureCardClick}
                        >
                            <ScheduleCard
                                heading="h3"
                                event={schedule}
                                status={
                                    <ScheduleCompletionBadge
                                        recorded={schedule.recorded}
                                        application={
                                            application?.id === schedule.id
                                        }
                                    />
                                }
                                evidence={schedule.evidence}
                            />
                        </li>
                    ))}
                </ul>
            </section>
        </div>
    );
}
