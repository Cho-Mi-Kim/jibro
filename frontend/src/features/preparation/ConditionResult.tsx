import { Link, useLocation } from 'react-router';
import type { Preparation, Question } from '../housing/types';
import { needsFollowUp, answerLabel } from '../housing/questions';
import { cx } from '../../shared/styles/classes';
import { Icon } from '../../shared/ui/Icon';
import { Card, EvidenceLink, InlineExplanation } from '../../shared/ui/ui';
import { Disc, Status, NoticeContext } from '../../shared/ui/HousingUI';
function ConditionEvidence({ question }: { question: Question }) {
    return (
        <InlineExplanation label={question.title + ' 공식 근거 보기'}>
            <EvidenceLink evidence={question.evidence} />
        </InlineExplanation>
    );
}
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
            <section className={cx('panel result-counts explanation-card')}>
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
            </section>
            <section className={cx('result-questions')}>
                <h2>추가로 확인할 조건</h2>
                {uncertain.length === 0 && (
                    <Card>추가 확인으로 남긴 조건이 없습니다.</Card>
                )}
                {uncertain.map((x) => (
                    <article
                        className={cx('panel result-question explanation-card')}
                        key={x.id}
                    >
                        <div className={cx('row')}>
                            <Disc name="warning" tone="amber" />
                            <Status tone="amber">추가 확인 필요</Status>
                        </div>
                        <h2>{x.title}</h2>
                        <p>내 답변: {answerLabel(x, p.answers[x.id])}</p>
                        <p className={cx('muted')}>{x.reason}</p>
                        <ConditionEvidence question={x} />
                        {!inTab && (
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
                    </article>
                ))}
                <details className={cx('card')}>
                    <summary>답변 완료 {confirmed.length}개 보기</summary>
                    {confirmed.map((x) => (
                        <div
                            className={cx(
                                'explanation-card confirmed-condition',
                            )}
                            key={x.id}
                        >
                            <h3>{x.title}</h3>
                            <p>{answerLabel(x, p.answers[x.id])}</p>
                            <ConditionEvidence question={x} />
                        </div>
                    ))}
                </details>
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
