import { Link, useLocation } from 'react-router';
import type { Preparation } from '../housing/types';
import {
    needsFollowUp,
    answerLabel,
    applicantQuestions,
} from '../housing/questions';
import { bindClasses } from '../../shared/styles/classes';
import { Icon } from '../../shared/ui/Icon';
import { Card, InlineExplanation } from '../../shared/ui/ui';
import { ConditionSummary } from '../housing/ConditionSummary';
import { Disc } from '../../shared/ui/Disc';
import { ConditionAnswerStatus } from './ConditionAnswerStatus';
import {
    conditionAnswerPresentation,
    pendingAnswersLabel,
} from './conditionAnswerPresentation';
import { AreaReviewIssues } from '../housing/ReviewIssues';
import { questionReviewIssues } from '../housing/reviewIssueSelectors';
import { NoticeContext } from './PreparationUI';
import questionsClasses from './Conditions.module.css';
import { PreparationScrollSection } from './PreparationScrollSection';
import { PersonalizedPreparationGuide } from './PersonalizedPreparationGuide';
import {
    visiblePreparationDocuments,
    visiblePreparationQuestions,
} from '../housing/preparationApplicability';
import { PreparationScopeNotice } from './PreparationScopeNotice';

const cx = bindClasses(questionsClasses);

export function ConditionResult({
    p,
    inTab,
}: {
    p: Preparation;
    inTab: boolean;
}) {
    const { state } = useLocation();
    const documents = visiblePreparationDocuments(p);
    const questions = applicantQuestions(
        visiblePreparationQuestions(p),
        Object.values(p.documentConditions ?? {}),
    );
    const uncertain = questions.filter((x) =>
        needsFollowUp(x, p.answers[x.id], p.answers, p.questions),
    );
    const confirmed = questions.filter(
        (x) => !needsFollowUp(x, p.answers[x.id], p.answers, p.questions),
    );
    return (
        <div className={cx('result-grid')}>
            <div className={cx('result-context')}>
                <NoticeContext p={p} />
                <PreparationScopeNotice id={p.id} />
            </div>
            <PreparationScrollSection
                className={cx('panel result-counts explanation-card')}
            >
                <div className={cx('row')}>
                    <div>
                        <Disc
                            name={conditionAnswerPresentation.complete.icon}
                            tone={conditionAnswerPresentation.complete.tone}
                        />
                        <span>
                            {conditionAnswerPresentation.complete.label}
                            <strong>{confirmed.length}개</strong>
                        </span>
                    </div>
                    <div>
                        <Disc
                            name={conditionAnswerPresentation.followUp.icon}
                            tone={conditionAnswerPresentation.followUp.tone}
                        />
                        <span>
                            {pendingAnswersLabel}
                            <strong>{uncertain.length}개</strong>
                        </span>
                    </div>
                </div>
                <InlineExplanation label="조건 확인 결과 안내">
                    <p className={cx('info-note')}>
                        입력한 내용에 대한 안내이며 최종 신청 자격을 뜻하지
                        않습니다.
                    </p>
                </InlineExplanation>
            </PreparationScrollSection>
            <div className={questionsClasses.personalizedGuide}>
                <PersonalizedPreparationGuide
                    guideState={p.guideState}
                    questions={p.questions}
                    answers={p.answers}
                    documents={p.documents}
                    reviewIssues={p.reviewIssues}
                    documentConditions={p.documentConditions}
                    documentConditionValues={p.documentConditionValues}
                />
            </div>
            <section className={cx('result-questions')}>
                <AreaReviewIssues area="CONDITION" issues={p.reviewIssues} />
                <h2>추가로 확인할 조건</h2>
                {uncertain.length === 0 && (
                    <Card>추가 확인으로 남긴 조건이 없습니다.</Card>
                )}
                {uncertain.map((x) => (
                    <PreparationScrollSection
                        as="article"
                        className={cx('panel result-question')}
                        key={x.id}
                    >
                        <ConditionSummary
                            question={x}
                            issues={questionReviewIssues(p.reviewIssues, x.id)}
                        >
                            <ConditionAnswerStatus
                                question={x}
                                answer={p.answers[x.id]}
                                answers={p.answers}
                                questions={p.questions}
                            />
                            {p.answers[x.id] !== undefined && (
                                <p>
                                    내 답변: {answerLabel(x, p.answers[x.id])}
                                </p>
                            )}
                            {x.reviewRequired && (
                                <p className={cx('muted')}>
                                    이 항목은 답변으로 확정하지 않아요. 기존
                                    답변은 보존됩니다.
                                </p>
                            )}
                        </ConditionSummary>
                        {!inTab && !x.reviewRequired && (
                            <Link
                                className={cx('result-action')}
                                state={state}
                                to={
                                    '/preparations/' +
                                    p.id +
                                    '/conditions/edit?' +
                                    new URLSearchParams({ question: x.id })
                                }
                            >
                                답변 이어서 작성 <Icon name="arrow" />
                            </Link>
                        )}
                    </PreparationScrollSection>
                ))}
                <PreparationScrollSection as="details" className={cx('card')}>
                    <summary>
                        {conditionAnswerPresentation.complete.label}{' '}
                        {confirmed.length}개 보기
                    </summary>
                    {confirmed.map((x) => (
                        <PreparationScrollSection
                            as="div"
                            className={cx('confirmed-condition')}
                            key={x.id}
                        >
                            <ConditionSummary
                                question={x}
                                issues={questionReviewIssues(
                                    p.reviewIssues,
                                    x.id,
                                )}
                                heading="h3"
                            >
                                <ConditionAnswerStatus
                                    question={x}
                                    answer={p.answers[x.id]}
                                    answers={p.answers}
                                    questions={p.questions}
                                />
                                <p>{answerLabel(x, p.answers[x.id])}</p>
                            </ConditionSummary>
                        </PreparationScrollSection>
                    ))}
                </PreparationScrollSection>
            </section>
            <aside
                className={cx('panel result-documents')}
                aria-label="개인별 서류"
            >
                <div className={cx('row')}>
                    <Disc name="file" />
                </div>
                <p>
                    필요 서류{' '}
                    {
                        documents.filter(
                            (d) =>
                                d.necessity === 'REQUIRED' ||
                                d.necessity === 'CONDITIONAL',
                        ).length
                    }
                    개
                </p>
                <p className={cx('muted')}>
                    해당 여부 추가 확인{' '}
                    {documents.filter((d) => d.necessity === 'UNKNOWN').length}
                    개
                </p>
                <Link
                    className={cx('link-button wide')}
                    to={'/preparations/' + p.id + '/documents'}
                >
                    내 준비 목록 보기
                </Link>
                {!inTab && (
                    <Link
                        className={cx('result-edit')}
                        state={state}
                        to={'/preparations/' + p.id + '/conditions/edit'}
                    >
                        답변 수정하기
                    </Link>
                )}
            </aside>
        </div>
    );
}
