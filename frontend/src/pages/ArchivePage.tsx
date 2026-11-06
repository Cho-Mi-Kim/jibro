import { useState } from 'react';
import { Link } from 'react-router';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../app/useSession';
import { useActivePreparation } from '../features/preparation/useActivePreparation';
import { memberApi } from '../features/member/memberApi';
import { ArchivePreparationSelect } from '../features/member/ArchivePreparationSelect';
import { Button, Heading, State } from '../shared/ui/ui';
import { Icon } from '../shared/ui/Icon';
import s from '../features/member/Controls.module.css';
import styles from './ArchivePage.module.css';

type ArchiveCommand =
    | { kind: 'archive'; id: string; revision: number; note: string }
    | { kind: 'restore'; id: string }
    | { kind: 'reuse'; sourceId: string; targetId: string };

export function ArchivePage() {
    const { user } = useSession(),
        client = useQueryClient(),
        { query: active, active: currentPreparation } = useActivePreparation();
    const list = useQuery({
        queryKey: ['archive', user?.id],
        queryFn: memberApi.archive,
    });
    const [target, setTarget] = useState(''),
        [notes, setNotes] = useState<Record<string, string>>({}),
        [message, setMessage] = useState('');
    const selected =
        active.data?.find((p) => p.id === target) ?? currentPreparation;
    const note = selected ? (notes[selected.id] ?? '') : '';
    const update = useMutation({
        mutationFn: async (op: ArchiveCommand) => {
            switch (op.kind) {
                case 'archive':
                    return memberApi.finish(op.id, op.revision, op.note);
                case 'restore':
                    return memberApi.restore(op.id);
                case 'reuse':
                    return memberApi.reuse(op.targetId, op.sourceId);
            }
        },
        onSuccess: async (_, op) => {
            if (op.kind === 'archive') {
                setNotes((drafts) => {
                    const remaining = { ...drafts };
                    delete remaining[op.id];
                    return remaining;
                });
            }
            setMessage(
                op.kind === 'reuse'
                    ? '현재 준비 건에 참고 메모를 연결했습니다.'
                    : op.kind === 'archive'
                      ? '보관했습니다.'
                      : '내 준비로 복원했습니다.',
            );
            await Promise.all([
                client.invalidateQueries({ queryKey: ['archive', user?.id] }),
                client.invalidateQueries({
                    queryKey: ['preparations', user?.id],
                }),
                client.invalidateQueries({
                    queryKey: ['preparation', user?.id],
                }),
                client.invalidateQueries({
                    queryKey: ['references', user?.id],
                }),
            ]);
        },
    });
    return (
        <>
            <Heading title="완료 여정 보관함" backTo="/my">
                준비 기록과 메모를 보관하고 다시 꺼내 보세요.
            </Heading>
            <State
                loading={active.isPending || list.isPending}
                error={active.error ?? list.error}
                retry={() => {
                    void active.refetch();
                    void list.refetch();
                }}
            />
            <div className={styles.layout}>
                {active.isSuccess && selected && (
                    <section
                        className={styles.card}
                        aria-labelledby="archive-current-title"
                    >
                        <header className={styles.sectionHeading}>
                            <Icon name="bookmark" />
                            <h2 id="archive-current-title">준비 기록 보관</h2>
                        </header>
                        <ArchivePreparationSelect
                            items={active.data}
                            value={selected.id}
                            onChange={setTarget}
                        />
                        <label className={styles.field}>
                            <span className={styles.fieldLabel}>
                                다음 준비에 참고할 메모 <span>선택</span>
                            </span>
                            <textarea
                                value={note}
                                disabled={update.isPending}
                                maxLength={500}
                                rows={3}
                                placeholder="다음에 기억하고 싶은 내용을 남겨 주세요."
                                onChange={(e) =>
                                    setNotes((drafts) => ({
                                        ...drafts,
                                        [selected.id]: e.target.value,
                                    }))
                                }
                            />
                        </label>
                        <div className={styles.notice}>
                            <Icon name="info" />
                            <p>
                                보관하면 진행 중 목록에서 빠지며,
                                <br />
                                답변·준비도·공식 신청 상태는 유지됩니다.
                            </p>
                        </div>
                        <Button
                            className="wide"
                            disabled={update.isPending}
                            onClick={() =>
                                update.mutate({
                                    kind: 'archive',
                                    id: selected.id,
                                    revision: selected.revision,
                                    note,
                                })
                            }
                        >
                            이 준비 건 보관
                        </Button>
                    </section>
                )}
                <section
                    className={styles.card}
                    aria-labelledby="archive-history-title"
                >
                    <header className={styles.sectionHeading}>
                        <h2 id="archive-history-title">보관 기록</h2>
                        {list.isSuccess && (
                            <span className={styles.count}>
                                {list.data.length}건
                            </span>
                        )}
                    </header>
                    {list.isSuccess && !list.data.length && (
                        <div className={styles.empty}>
                            <Icon name="book" />
                            <p>아직 보관한 준비가 없어요.</p>
                            <span>보관한 기록과 메모가 여기에 모여요.</span>
                        </div>
                    )}
                    <ul className={styles.records}>
                        {list.data?.map((x) => (
                            <li key={x.id}>
                                <h3>{x.title}</h3>
                                <p className={styles.metadata}>
                                    안내 v{x.version} · 기록한 준비도{' '}
                                    {x.progress}%
                                </p>
                                <p className={styles.note}>
                                    {x.note || '남긴 경험 메모가 없습니다.'}
                                </p>
                                <div className={styles.actions}>
                                    <Button
                                        className="secondary"
                                        disabled={update.isPending}
                                        onClick={() =>
                                            update.mutate({
                                                kind: 'restore',
                                                id: x.id,
                                            })
                                        }
                                    >
                                        내 준비로 복원
                                    </Button>
                                    {selected && x.note && (
                                        <Button
                                            className="secondary"
                                            disabled={update.isPending}
                                            onClick={() =>
                                                update.mutate({
                                                    kind: 'reuse',
                                                    sourceId: x.id,
                                                    targetId: selected.id,
                                                })
                                            }
                                        >
                                            현재 건에 메모 연결
                                        </Button>
                                    )}
                                </div>
                            </li>
                        ))}
                    </ul>
                </section>
                {message && <p role="status">{message}</p>}
                {update.error && <p role="alert">{update.error.message}</p>}
                <Link className={styles.backLink} to="/preparations">
                    내 준비 보기
                </Link>
            </div>
        </>
    );
}
export function ExperienceNotes({ id }: { id: string }) {
    const { user } = useSession();
    const q = useQuery({
        queryKey: ['references', user?.id, id],
        queryFn: () => memberApi.references(id),
    });
    return (
        <>
            {q.isError && (
                <State error={q.error} retry={() => void q.refetch()} />
            )}{' '}
            {!!q.data?.length && (
                <section className={s.section}>
                    <h2>이전 여정에서 가져온 메모</h2>
                    {q.data.map((x) => (
                        <p key={x.id}>
                            <strong>{x.title}</strong>
                            <br />
                            {x.note}
                        </p>
                    ))}
                </section>
            )}
        </>
    );
}
