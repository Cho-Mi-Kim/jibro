import { Link, useLocation } from 'react-router';
import type { Preparation } from '../housing/types';
import { needsFollowUp, answerLabel } from '../housing/questions';
import { bindClasses } from '../../shared/styles/classes';
import { Icon } from '../../shared/ui/Icon';
import { Card, InlineExplanation } from '../../shared/ui/ui';
import { ConditionSummary } from '../housing/ConditionSummary';
import { Disc } from '../../shared/ui/Disc';
import { Status } from '../../shared/ui/Status';
import { PartialGuidanceNotice, ReviewIssues } from '../housing/ReviewIssues';
import { questionReviewIssues } from '../housing/reviewIssueSelectors';
import { NoticeContext } from './PreparationUI';
import questionsClasses from './Conditions.module.css';
import { PreparationScrollSection } from './PreparationScrollSection';

const cx = bindClasses(questionsClasses);

export function ConditionResult({
    p,
    inTab,
}: {
    p: Preparation;
    inTab: boolean;
}) {
    const { state } = useLocation();
    const uncertain = p.questions.filter((x) =>
        needsFollowUp(x, p.answers[x.id]),
    );
    const confirmed = p.questions.filter(
        (x) => !needsFollowUp(x, p.answers[x.id]),
    );
    return (
        <div className={cx('result-grid')}>
            <div className={cx('result-context')}>
                <NoticeContext p={p} />
            </div>
            <PreparationScrollSection
                className={cx('panel result-counts explanation-card')}
            >
                <div className={cx('row')}>
                    <div>
                        <Disc name="check" tone="green" />
                        <span>
                            답변 완료
                            <strong>{confirmed.length}개</strong>
                        </span>
                    </div>
                    <div>
                        <Disc name="warning" tone="amber" />
                        <span>
                            추가 확인
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
            <section className={cx('result-questions')}>
                <PartialGuidanceNotice issues={p.reviewIssues} />
                <ReviewIssues
                    issues={(p.reviewIssues ?? []).filter(
                        (issue) =>
                            issue.area === 'CONDITION' &&
                            !issue.questionIds.length,
                    )}
                />
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
                        <div className={cx('row')}>
                            <Disc name="warning" tone="amber" />
                            <Status tone="amber">
                                {x.reviewRequired
                                    ? '공식 확인 필요'
                                    : '추가 확인 필요'}
                            </Status>
                        </div>
                        <ConditionSummary
                            question={x}
                            issues={questionReviewIssues(p.reviewIssues, x.id)}
                        >
                            {(!x.reviewRequired ||
                                p.answers[x.id] !== undefined) && (
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
                    <summary>답변 완료 {confirmed.length}개 보기</summary>
                    {confirmed.map((x) => (
                        <div className={cx('confirmed-condition')} key={x.id}>
                            <ConditionSummary
                                question={x}
                                issues={questionReviewIssues(
                                    p.reviewIssues,
                                    x.id,
                                )}
                                heading="h3"
                            >
                                <p>{answerLabel(x, p.answers[x.id])}</p>
                            </ConditionSummary>
                        </div>
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
                        p.documents.filter(
                            (d) =>
                                d.necessity === 'REQUIRED' ||
                                d.necessity === 'CONDITIONAL',
                        ).length
                    }
                    개
                </p>
                <p className={cx('muted')}>
                    해당 여부 추가 확인{' '}
                    {
                        p.documents.filter((d) => d.necessity === 'UNKNOWN')
                            .length
                    }
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
