import type {
    Answer,
    DocumentItem,
    DocumentTemplate,
    GuideState,
    Question,
    ReviewIssue,
} from '../housing/types';
import {
    answerLabel,
    applicantQuestions,
    isAcknowledgementQuestion,
    needsFollowUp,
} from '../housing/questions';
import { answerConflictMessages } from '../housing/answerDependencies';
import {
    areaReviewIssues,
    documentReviewIssues,
    questionReviewIssues,
} from '../housing/reviewIssueSelectors';
import { ConditionSummary } from '../housing/ConditionSummary';
import { EvidenceLink } from '../housing/EvidenceLink';
import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';
import { useScrollToCard } from '../../shared/ui/useScrollToCard';
import styles from './PersonalizedPreparationGuide.module.css';
import {
    visiblePreparationQuestions,
    visiblePreparationDocuments,
} from '../housing/preparationApplicability';

interface GuideProps {
    guideState: GuideState;
    questions: Question[];
    answers: Record<string, Answer>;
    documents: DocumentItem[];
    reviewIssues?: ReviewIssue[];
    documentConditions?: Record<string, string>;
    documentConditionValues?: Record<string, string[]>;
    templates?: DocumentTemplate[] | null;
}

function situationLabel(question: Question) {
    const title = question.title.replace(/\s+/gu, '');
    if (/누가.{0,12}(?:방문|신청)|(?:대신|대리).{0,12}신청/u.test(title))
        return '신청하는 사람';
    if (
        /(?:신청|접수).{0,12}(?:방법|방식)|(?:방법|방식).{0,12}(?:신청|접수)/u.test(
            title,
        )
    )
        return '신청 방법';
    if (
        /(?:신청|공급).{0,12}(?:유형|구분)|(?:유형|구분).{0,12}신청/u.test(
            title,
        )
    )
        return '신청 유형';
    return null;
}

function concreteChoice(question: Question, answer: Answer | undefined) {
    if (answer === undefined || !question.options?.length) return false;
    const values = Array.isArray(answer) ? answer : [answer];
    return values.every((value) => {
        if (
            typeof value !== 'string' ||
            [
                'ACK',
                'CHECKED',
                'CONFIRMED',
                'MEETS',
                'DOES_NOT_MEET',
                'NONE',
            ].includes(value)
        )
            return false;
        const label = question.options?.find(
            (option) => option.value === value,
        )?.label;
        return (
            !!label &&
            !/^(?:네|예|아니요|(?:안내(?:내용을(?:모두)?)?를?)?확인했어요|아직확인하지않았어요|확인전|해당해요|해당하지않아요|(?:다른|그외|기타)유형(?:으로)?(?:신청해요|신청)?|(?:내)?신청유형기준에맞아요|해당하는유형(?:이)?없어요)[.!]?$/u.test(
                label.replace(/\s+/gu, ''),
            )
        );
    });
}

function matches(answer: Answer | undefined, values: readonly string[]) {
    return (Array.isArray(answer) ? answer : [answer]).some(
        (value) => typeof value === 'string' && values.includes(value),
    );
}

function ReferenceQuestion({ question }: { question: Question }) {
    const { cardRef, scrollToCard } = useScrollToCard<HTMLLIElement>(
        '[data-preparation-panel], [data-question-page]',
    );
    return (
        <li
            ref={cardRef}
            onClick={(event) => handleDisclosureCardClick(event, scrollToCard)}
        >
            <ConditionSummary question={question} issues={[]} heading="h4" />
        </li>
    );
}

/** Use saved answer and document contracts; never infer an application route from prose. */
export function PersonalizedPreparationGuide(props: GuideProps) {
    const { cardRef: guidanceRef, scrollToCard: scrollToGuidance } =
        useScrollToCard<HTMLDetailsElement>(
            '[data-preparation-panel], [data-question-page]',
        );
    const {
        guideState,
        questions,
        answers,
        reviewIssues,
        documentConditions,
        documentConditionValues,
        templates,
    } = props;
    const documentQuestionIds = [
        ...Object.values(documentConditions ?? {}),
        ...(templates ?? []).flatMap((template) =>
            template.conditionQuestionId ? [template.conditionQuestionId] : [],
        ),
    ];
    const visible = visiblePreparationQuestions(props);
    const documents = visiblePreparationDocuments(props);
    const personal = applicantQuestions(visible, documentQuestionIds);
    const personalIds = new Set(personal.map((question) => question.id));
    const conflictIds = new Set<string>();
    for (const question of questions) {
        const messages = answerConflictMessages(
            question,
            answers[question.id],
            answers,
            questions,
        );
        if (!messages.length) continue;
        conflictIds.add(question.id);
        for (const dependency of question.answerDependencies ?? [])
            if (messages.includes(dependency.message))
                conflictIds.add(dependency.questionId);
    }
    const broadReview = areaReviewIssues(reviewIssues, 'CONDITION').length > 0;
    const situations = personal.flatMap((question) => {
        const label = situationLabel(question);
        if (!label || isAcknowledgementQuestion(question)) return [];
        const review =
            broadReview ||
            question.reviewRequired ||
            questionReviewIssues(reviewIssues, question.id).length > 0;
        const conflict = conflictIds.has(question.id);
        const confirmed =
            !review &&
            !conflict &&
            !needsFollowUp(
                question,
                answers[question.id],
                answers,
                questions,
            ) &&
            concreteChoice(question, answers[question.id]);
        const related =
            confirmed && !areaReviewIssues(reviewIssues, 'DOCUMENT').length
                ? documents.filter((document) => {
                      if (
                          !['REQUIRED', 'CONDITIONAL'].includes(
                              document.necessity,
                          ) ||
                          document.reviewRequired
                      )
                          return false;
                      const template = templates?.find(
                          (item) => item.id === document.id,
                      );
                      const questionId =
                          documentConditions?.[document.id] ??
                          template?.conditionQuestionId;
                      if (
                          documentReviewIssues(
                              reviewIssues,
                              document.id,
                              questionId,
                          ).length
                      )
                          return false;
                      const direct =
                          questionId === question.id &&
                          matches(
                              answers[question.id],
                              documentConditionValues?.[document.id] ??
                                  template?.conditionAnswerValues ?? ['YES'],
                          );
                      const dependent = questions.some(
                          (item) =>
                              !item.reviewRequired &&
                              !questionReviewIssues(reviewIssues, item.id)
                                  .length &&
                              (item.answerDependencies ?? []).some(
                                  (dependency) =>
                                      dependency.questionId === question.id &&
                                      dependency.requiredDocumentIds.includes(
                                          document.id,
                                      ) &&
                                      matches(
                                          answers[question.id],
                                          dependency.answerValues,
                                      ),
                              ),
                      );
                      return direct || dependent;
                  })
                : [];
        return [{ question, label, confirmed, related, review, conflict }];
    });
    const guidance = visible.filter(
        (question) =>
            !personalIds.has(question.id) &&
            isAcknowledgementQuestion(question) &&
            !broadReview &&
            !question.reviewRequired &&
            !questionReviewIssues(reviewIssues, question.id).length,
    );

    if (guideState !== 'AVAILABLE') return null;
    if (!situations.length && !guidance.length) return null;
    return (
        <section className={styles.guide} aria-label="내 답변에 맞춘 준비 안내">
            <h2>내 답변에 맞춘 준비 안내</h2>
            {situations.length > 0 && (
                <ul className={styles.situations}>
                    {situations.map(
                        ({
                            question,
                            label,
                            confirmed,
                            related,
                            review,
                            conflict,
                        }) => (
                            <li key={question.id}>
                                <h3>{label}</h3>
                                {confirmed ? (
                                    <>
                                        <p className={styles.choice}>
                                            {answerLabel(
                                                question,
                                                answers[question.id],
                                            )}
                                        </p>
                                        {related.length > 0 && (
                                            <p className={styles.documents}>
                                                준비할 서류:{' '}
                                                {related
                                                    .map(
                                                        (document) =>
                                                            document.title,
                                                    )
                                                    .join(' · ')}
                                            </p>
                                        )}
                                    </>
                                ) : (
                                    <p className={styles.pending}>
                                        {review
                                            ? '공식 안내를 확인해야 해요.'
                                            : conflict
                                              ? '다른 답변과 맞지 않아 다시 확인해야 해요.'
                                              : '구체적인 선택을 확인한 뒤 준비 내용을 정리해요.'}
                                    </p>
                                )}
                                <EvidenceLink evidence={question.evidence} />
                            </li>
                        ),
                    )}
                </ul>
            )}
            {guidance.length > 0 && (
                <details
                    className={styles.guidance}
                    ref={guidanceRef}
                    onToggle={(event) => {
                        if (event.target === event.currentTarget)
                            scrollToGuidance(event.currentTarget);
                    }}
                >
                    <summary>공통·맞춤 준비 안내 {guidance.length}개</summary>
                    <ul>
                        {guidance.map((question) => (
                            <ReferenceQuestion
                                key={question.id}
                                question={question}
                            />
                        ))}
                    </ul>
                </details>
            )}
        </section>
    );
}
