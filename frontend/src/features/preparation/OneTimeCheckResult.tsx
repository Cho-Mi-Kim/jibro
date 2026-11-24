import { ConditionAnswerStatus } from './ConditionAnswerStatus';
import {
    conditionAnswerPresentation,
    pendingAnswersLabel,
} from './conditionAnswerPresentation';
import {
    answerLabel,
    needsFollowUp,
    applicantQuestions,
} from '../housing/questions';
import { ConditionSummary } from '../housing/ConditionSummary';
import {
    AreaReviewIssues,
    PartialGuidanceNotice,
} from '../housing/ReviewIssues';
import { questionReviewIssues } from '../housing/reviewIssueSelectors';
import { ScheduleCard } from '../housing/ScheduleCard';
import type { Notice } from '../housing/types';
import type { DocumentTemplate, GuideState } from '../housing/types';
import { OneTimeDocumentSummary } from './OneTimeDocumentSummary';
import { Button, Card } from '../../shared/ui/ui';
import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';
import { useResultCardScroll } from '../../shared/ui/useResultCardScroll';
import type { OneTimeCheckResult as Result } from './oneTimeCheckApi';
import styles from './OneTimeCheck.module.css';
import { PersonalizedPreparationGuide } from './PersonalizedPreparationGuide';
import {
    visiblePreparationQuestions,
    visiblePreparationDocuments,
    preparationScheduleSelection,
} from '../housing/preparationApplicability';

export function OneTimeCheckResult({
    result,
    templates,
    schedules = [],
    guideState,
    onEdit,
    onClear,
}: {
    result: Result;
    templates?: DocumentTemplate[] | null;
    schedules?: Notice['schedules'];
    guideState: GuideState;
    onEdit: () => void;
    onClear: () => void;
}) {
    const resultsRef = useResultCardScroll('[data-question-page]');
    const documentConditions = Object.fromEntries(
        (templates ?? []).flatMap((document) =>
            document.conditionQuestionId
                ? [[document.id, document.conditionQuestionId]]
                : [],
        ),
    );
    const scope = { ...result, guideState, documentConditions };
    const questions = applicantQuestions(
        visiblePreparationQuestions(scope),
        Object.values(documentConditions),
    );
    const uncertain = questions.filter((question) =>
        needsFollowUp(
            question,
            result.answers[question.id],
            result.answers,
            result.questions,
        ),
    );
    const documents = visiblePreparationDocuments(scope);
    const visibleSchedules = preparationScheduleSelection(
        scope,
        schedules,
    ).visible;
    return (
        <div className={styles.results} ref={resultsRef}>
            <PersonalizedPreparationGuide
                guideState={guideState}
                questions={result.questions}
                answers={result.answers}
                documents={result.documents}
                documentConditions={documentConditions}
                reviewIssues={result.reviewIssues}
                templates={templates ?? undefined}
            />
            <Card>
                <h2>이번 조건 확인 결과</h2>
                <p>
                    내 답변에 맞는 조건과 서류, 공통 안내를 모았어요. 아직
                    확인할 항목도 함께 보여드려요.
                </p>
                <PartialGuidanceNotice issues={result.reviewIssues} />
                <p>
                    {conditionAnswerPresentation.complete.label}{' '}
                    {questions.length - uncertain.length}개 ·{' '}
                    {pendingAnswersLabel} {uncertain.length}개
                </p>
                <p className={styles.privacy}>
                    입력한 내용에 대한 안내이며 최종 신청 자격을 뜻하지
                    않습니다. 이 결과는 내 준비 목록에 저장되지 않습니다.
                </p>
                <AreaReviewIssues
                    area="CONDITION"
                    issues={result.reviewIssues}
                />
                <ul className={styles.conditionList}>
                    {questions.map((question) => (
                        <li
                            key={question.id}
                            data-result-card
                            onClick={handleDisclosureCardClick}
                        >
                            <ConditionSummary
                                question={question}
                                heading="h3"
                                issues={questionReviewIssues(
                                    result.reviewIssues,
                                    question.id,
                                )}
                            >
                                <ConditionAnswerStatus
                                    question={question}
                                    answer={result.answers[question.id]}
                                    answers={result.answers}
                                    questions={result.questions}
                                />
                                {result.answers[question.id] !== undefined && (
                                    <p>
                                        내 답변:{' '}
                                        {answerLabel(
                                            question,
                                            result.answers[question.id],
                                        )}
                                    </p>
                                )}
                            </ConditionSummary>
                        </li>
                    ))}
                </ul>
            </Card>
            <Card>
                <h2>확인할 서류 {documents.length}개</h2>
                <AreaReviewIssues
                    area="DOCUMENT"
                    issues={result.reviewIssues}
                />
                {documents.length === 0 && (
                    <p>
                        현재 답변에 연결된 서류가 없습니다. 공고 원문을 함께
                        확인해 주세요.
                    </p>
                )}
                {documents.map((document) => (
                    <OneTimeDocumentSummary
                        key={document.id}
                        document={document}
                        template={templates?.find(
                            (item) => item.id === document.id,
                        )}
                        result={result}
                    />
                ))}
            </Card>
            {schedules.length > 0 && (
                <Card>
                    <h2>확인할 일정</h2>
                    <AreaReviewIssues
                        area="SCHEDULE"
                        issues={result.reviewIssues}
                    />
                    <ul className={styles.conditionList}>
                        {visibleSchedules.map((schedule) => (
                            <li
                                key={schedule.id}
                                data-result-card
                                onClick={handleDisclosureCardClick}
                            >
                                <ScheduleCard
                                    heading="h3"
                                    event={schedule}
                                    evidence={schedule.evidence}
                                />
                            </li>
                        ))}
                    </ul>
                </Card>
            )}
            <div className={styles.actions}>
                <Button className="secondary" onClick={onEdit}>
                    답변 수정
                </Button>
                <Button className="secondary" onClick={onClear}>
                    답변과 결과 지우기
                </Button>
            </div>
        </div>
    );
}
