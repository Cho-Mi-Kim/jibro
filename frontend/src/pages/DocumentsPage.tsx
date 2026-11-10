import { cx } from '../shared/styles/classes';
import { useState } from 'react';
import { Link } from 'react-router';
import { Card, State } from '../shared/ui/ui';
import { safeUrl } from '../shared/ui/helpers';
import { Icon } from '../shared/ui/Icon';
import { PreparationGuidance } from '../features/preparation/PreparationNav';
import { NoticeContext } from '../shared/ui/HousingUI';
import { usePreparation } from '../features/preparation/usePreparation';
import { DocumentEditor } from '../features/preparation/DocumentEditor';
export function DocumentsPage() {
    const q = usePreparation();
    const p = q.data;
    const [selected, setSelected] = useState<string | null>(null);
    const docs =
        p?.documents.filter(
            (d) => d.necessity === 'REQUIRED' || d.necessity === 'CONDITIONAL',
        ) ?? [];
    const ready = docs.filter((d) => d.state === 'READY').length;
    const unresolved =
        p?.documents.filter((d) => d.necessity === 'UNKNOWN') ?? [];
    const active =
        selected === ''
            ? ''
            : docs.some((d) => d.id === selected)
              ? selected
              : docs[0]?.id;
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
                    <div className={cx('documents-layout')}>
                        <div className={cx('document-progress')}>
                            <h2>
                                서류 {docs.length}개 중 <em>{ready}개</em> 준비
                                완료
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
                                준비 완료 {ready}개 · 해당 여부 미확인{' '}
                                {unresolved.length}개
                            </small>
                        </div>
                        {(['REQUIRED', 'CONDITIONAL'] as const).map(
                            (necessity) => {
                                const group = docs.filter(
                                    (d) => d.necessity === necessity,
                                );
                                if (!group.length) return null;
                                return (
                                    <section
                                        key={necessity}
                                        className={cx('document-group')}
                                    >
                                        <h2>
                                            {necessity === 'REQUIRED'
                                                ? '공통 필수'
                                                : '해당 조건부'}
                                        </h2>
                                        {group.map((d) => (
                                            <DocumentEditor
                                                key={p.id + ':' + d.id}
                                                p={p}
                                                d={d}
                                                active={active === d.id}
                                                onSelect={() =>
                                                    setSelected(
                                                        active === d.id
                                                            ? ''
                                                            : d.id,
                                                    )
                                                }
                                            />
                                        ))}
                                    </section>
                                );
                            },
                        )}
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
                                {unresolved.map((d) => (
                                    <li key={d.id}>{d.title}</li>
                                ))}
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
                            {docs.some((doc) => !safeUrl(doc.issueUrl)) && (
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
