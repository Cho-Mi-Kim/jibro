import { DocumentStateBadge } from '../features/preparation/DocumentStateBadge';
import { documentStatePresentation } from '../features/preparation/documentStatePresentation';
import { AreaReviewIssues } from '../features/housing/ReviewIssues';
import { bindClasses } from '../shared/styles/classes';
import { useEffect, useRef, useState } from 'react';
import { animateScroll } from '../shared/ui/animateScroll';
import { prefersReducedMotion } from '../shared/ui/motionPreferences';
import { Link } from 'react-router';
import { Card, State } from '../shared/ui/ui';
import { onlineIssuanceLinks } from '../features/housing/onlineIssuance';
import { Icon } from '../shared/ui/Icon';
import { PreparationGuidance } from '../features/preparation/PreparationNav';
import { NoticeContext } from '../features/preparation/PreparationUI';
import { usePreparation } from '../features/preparation/usePreparation';
import { documentSummary } from '../features/housing/documentSummary';
import { DocumentCard } from '../features/housing/DocumentCard';
import { DocumentEditor } from '../features/preparation/DocumentEditor';
import { documentNeedsReview } from '../features/housing/reviewIssueSelectors';
import documentsClasses from '../features/preparation/Documents.module.css';
import { PreparationScopeNotice } from '../features/preparation/PreparationScopeNotice';
import { visiblePreparationDocuments } from '../features/housing/preparationApplicability';
import type { Preparation } from '../features/housing/types';

const cx = bindClasses(documentsClasses);

export function DocumentsPage() {
    const q = usePreparation();
    const p = q.data;
    const [active, setSelected] = useState('');
    const [scrollRequest, requestScroll] = useState(0);
    const layoutRef = useRef<HTMLDivElement>(null);
    useEffect(() => {
        if (!scrollRequest || active) return;
        const panel = layoutRef.current?.closest<HTMLElement>(
            '[data-preparation-panel]',
        );
        const target =
            panel && /auto|scroll/.test(getComputedStyle(panel).overflowY)
                ? panel
                : window;
        return animateScroll(target, 0, prefersReducedMotion());
    }, [active, scrollRequest]);
    function openNextDocument(next: Preparation) {
        const remaining = visiblePreparationDocuments(next).filter(
            (d) =>
                d.state !== 'READY' &&
                !documentNeedsReview(d, next.reviewIssues),
        );
        const first =
            remaining.find((d) => d.necessity === 'REQUIRED') ??
            remaining.find((d) => d.necessity === 'CONDITIONAL');
        setSelected(first?.id ?? '');
        requestScroll((value) => value + 1);
        return true;
    }
    const documents = p ? visiblePreparationDocuments(p) : [];
    const docs = documents.filter(
        (d) =>
            !documentNeedsReview(d, p?.reviewIssues) &&
            (d.necessity === 'REQUIRED' || d.necessity === 'CONDITIONAL'),
    );
    const ready = docs.filter((d) => d.state === 'READY').length;
    const unresolved = documents.filter(
        (d) => !docs.includes(d) && !documentNeedsReview(d, p?.reviewIssues),
    );
    const officialReview = documents.filter((d) =>
        documentNeedsReview(d, p?.reviewIssues),
    );
    const groups = [
        {
            title: '공통 필수',
            items: docs.filter(
                (d) => d.state !== 'READY' && d.necessity === 'REQUIRED',
            ),
        },
        {
            title: '내 상황에 필요한 서류',
            items: docs.filter(
                (d) => d.state !== 'READY' && d.necessity === 'CONDITIONAL',
            ),
        },
        {
            title: `공식 확인이 필요한 서류 ${officialReview.length}개`,
            items: officialReview,
            description:
                '이 항목은 공식 자료의 적용 기준을 확인해야 해요. 답변을 바꾸어도 준비 완료로 처리되지 않으며, 다른 서류는 계속 준비할 수 있어요.',
        },
        {
            title: documentStatePresentation.READY.label,
            items: docs.filter((d) => d.state === 'READY'),
        },
    ];
    return (
        <>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {p && (
                <>
                    <NoticeContext p={p} />
                    <PreparationGuidance p={p} />
                    <PreparationScopeNotice id={p.id} />
                    <AreaReviewIssues area="DOCUMENT" issues={p.reviewIssues} />
                    <div ref={layoutRef} className={cx('documents-layout')}>
                        <div className={cx('document-progress')}>
                            <h2>
                                서류 {docs.length}개 중 <em>{ready}개</em>{' '}
                                {documentStatePresentation.READY.label}
                            </h2>
                            <div className={cx('row')}>
                                <strong>
                                    {ready} / {docs.length}
                                </strong>
                                <progress
                                    aria-label="서류 준비 진행률"
                                    max={Math.max(1, docs.length)}
                                    value={ready}
                                />
                                <span>
                                    {docs.length
                                        ? Math.round(
                                              (ready / docs.length) * 100,
                                          )
                                        : 0}
                                    %
                                </span>
                            </div>
                            <small>
                                {documentStatePresentation.READY.label} {ready}
                                개 · 해당 여부 미확인 {unresolved.length}개
                                {officialReview.length > 0 && (
                                    <>
                                        {' '}
                                        · 공식 확인 필요 {officialReview.length}
                                        개
                                    </>
                                )}
                            </small>
                        </div>
                        <div className={cx('document-group')}>
                            {groups.flatMap((group) =>
                                group.items.length > 0
                                    ? [
                                          <h2 key={'heading:' + group.title}>
                                              {group.title}
                                          </h2>,
                                          group.description && (
                                              <p key={'note:' + group.title}>
                                                  {group.description}
                                              </p>
                                          ),
                                          ...group.items.map((d) => (
                                              <DocumentEditor
                                                  key={p.id + ':' + d.id}
                                                  p={p}
                                                  d={d}
                                                  active={active === d.id}
                                                  onReady={openNextDocument}
                                                  scrollRequest={
                                                      active === d.id
                                                          ? scrollRequest
                                                          : 0
                                                  }
                                                  onSelect={() => {
                                                      requestScroll(0);
                                                      setSelected(
                                                          active === d.id
                                                              ? ''
                                                              : d.id,
                                                      );
                                                  }}
                                              />
                                          )),
                                      ]
                                    : [],
                            )}
                        </div>
                        {docs.length === 0 && (
                            <Card>
                                현재 답변으로 확인된 준비 대상 서류가 없습니다.
                            </Card>
                        )}
                    </div>
                    {unresolved.length > 0 && (
                        <Card>
                            <h2>해당 여부 추가 확인 {unresolved.length}개</h2>
                            <p>
                                아직 준비 대상인지 정하지 못한 서류가 있어요.
                                조건에 답하면 필요한 서류만 목록에 추가됩니다.
                            </p>
                            <strong>확인이 필요한 항목</strong>
                            <ul>
                                {unresolved.map((d) => {
                                    const summary = documentSummary(d, p);
                                    return (
                                        <li key={d.id}>
                                            <DocumentCard
                                                title={d.title}
                                                summary={summary}
                                            >
                                                <DocumentStateBadge
                                                    state={d.state}
                                                />
                                            </DocumentCard>
                                        </li>
                                    );
                                })}
                            </ul>
                            <Link
                                className={cx('button secondary wide')}
                                to={
                                    '/preparations/' + p.id + '/conditions/edit'
                                }
                            >
                                조건 답변 보완하기
                            </Link>
                        </Card>
                    )}
                    {docs.length > 0 && (
                        <footer className={cx('document-issue-note')}>
                            {docs.some(
                                (doc) => onlineIssuanceLinks(doc).length === 0,
                            ) && (
                                <p>
                                    온라인 발급 주소가 표시되지 않은 서류는 각
                                    서류의 공식 근거에서 제출 방법과 발급 기관을
                                    확인해 주세요.
                                </p>
                            )}
                            <small>
                                발급 안내 열람은 준비 상태를 변경하지 않습니다.
                            </small>
                            <p className={cx('muted info-line')}>
                                <Icon name="info" />
                                서류 원본은 올리지 않고 준비 상태만 기록합니다.
                            </p>
                        </footer>
                    )}
                </>
            )}
        </>
    );
}
