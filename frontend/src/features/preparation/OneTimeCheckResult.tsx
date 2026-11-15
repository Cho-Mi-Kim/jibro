import { answerLabel, needsFollowUp } from '../housing/questions';
import { ConditionSummary } from '../housing/ConditionSummary';
import { PartialGuidanceNotice } from '../housing/ReviewIssues';
import { questionReviewIssues } from '../housing/reviewIssueSelectors';
import type { DocumentTemplate } from '../housing/types';
import { OneTimeDocumentSummary } from './OneTimeDocumentSummary';
import { Button, Card } from '../../shared/ui/ui';
import type { OneTimeCheckResult as Result } from './oneTimeCheckApi';
import styles from './OneTimeCheck.module.css';

export function OneTimeCheckResult({
    result,
    templates,
    onEdit,
    onClear,
}: {
    result: Result;
    templates?: DocumentTemplate[] | null;
    onEdit: () => void;
    onClear: () => void;
}) {
    const uncertain = result.questions.filter((question) =>
        needsFollowUp(question, result.answers[question.id]),
    );
    const documents = result.documents.filter(
        (document) => document.necessity !== 'NOT_APPLICABLE',
    );
    return (
        <div className={styles.results}>
            <Card>
                <h2>이번 조건 확인 결과</h2>
                <PartialGuidanceNotice issues={result.reviewIssues} />
                <p>
                    답변 완료 {result.questions.length - uncertain.length}개 ·
                    추가 확인 {uncertain.length}개
                </p>
                <p className={styles.privacy}>
                    입력한 내용에 대한 안내이며 최종 신청 자격을 뜻하지
                    않습니다. 이 결과는 내 준비 목록에 저장되지 않습니다.
                </p>
                <ul className={styles.conditionList}>
                    {result.questions.map((question) => (
                        <li key={question.id}>
                            <ConditionSummary
                                question={question}
                                heading="h3"
                                issues={questionReviewIssues(
                                    result.reviewIssues,
                                    question.id,
                                )}
                            >
                                {(!question.reviewRequired ||
                                    result.answers[question.id] !==
                                        undefined) && (
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
