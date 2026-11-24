import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api } from '../housing/api';
import {
    emptyFollowUp,
    followUpResults,
    followUpSchedules,
    validatedFollowUp,
} from '../housing/followUp';
import type { FollowUpRecord, Preparation } from '../housing/types';
import { ScheduleCard } from '../housing/ScheduleCard';
import { Button } from '../../shared/ui/ui';
import { Icon } from '../../shared/ui/Icon';
import { useUnsavedChanges } from '../../shared/ui/useUnsavedChanges';
import { useResultCardScroll } from '../../shared/ui/useResultCardScroll';
import { useSaved } from './usePreparation';
import { ApiError } from '../../shared/api/client';
import styles from './FollowUp.module.css';

const steps = [
    {
        key: 'documentsSubmitted',
        title: '서류 제출·보완',
        label: '서류 제출 완료로 기록',
        hint: '내게 요청된 서류와 제출 기한을 확인해 주세요.',
    },
    {
        key: 'contractCompleted',
        title: '계약',
        label: '계약 완료로 기록',
        hint: '계약 일정·장소·준비물은 공식 안내에서 확인해 주세요.',
    },
    {
        key: 'movedIn',
        title: '입주',
        label: '입주 완료로 기록',
        hint: '안내받은 입주 가능 기간과 입주 절차를 확인해 주세요.',
    },
] as const;

export function FollowUpEditor({
    preparation: p,
}: {
    preparation: Preparation;
}) {
    const [draft, setDraft] = useState<FollowUpRecord | null>(null);
    const [message, setMessage] = useState('');
    const record = draft ?? p.followUp ?? emptyFollowUp;
    const official = followUpSchedules(p);
    const saved = useSaved();
    const cardRef = useResultCardScroll('[data-preparation-panel]');
    const dirty =
        !!draft &&
        JSON.stringify(draft) !== JSON.stringify(p.followUp ?? emptyFollowUp);
    useUnsavedChanges(dirty);
    const update = useMutation({
        mutationFn: (value: FollowUpRecord) =>
            api.followUp(p.id, p.revision, validatedFollowUp(value)),
        onSuccess: (next) => {
            saved(next);
            setDraft(null);
            setMessage('신청 후 진행 상황을 저장했어요.');
        },
    });
    const reload = useMutation({
        mutationFn: () => api.preparation(p.id),
        onSuccess: (next) => {
            saved(next);
            setDraft(null);
            update.reset();
            setMessage(
                '최신 기록을 불러왔어요. 변경할 내용을 다시 선택해 주세요.',
            );
        },
    });
    const pending = update.isPending || reload.isPending;
    const conflict =
        update.error instanceof ApiError && update.error.status === 409;
    function change(next: Partial<FollowUpRecord>) {
        setDraft({ ...record, ...next });
        setMessage('');
        update.reset();
    }
    return (
        <div ref={cardRef} data-follow-up-root>
            <form
                className={styles.editor}
                onSubmit={(e) => {
                    e.preventDefault();
                    update.mutate(record);
                }}
            >
                <fieldset className={styles.card} disabled={pending}>
                    <legend>입주자 선정 결과</legend>
                    <div className={styles.choices}>
                        {(
                            Object.entries(followUpResults) as [
                                FollowUpRecord['result'],
                                string,
                            ][]
                        ).map(([value, label]) => (
                            <label key={value}>
                                <input
                                    type="radio"
                                    name="follow-up-result"
                                    value={value}
                                    aria-label={label}
                                    checked={record.result === value}
                                    onChange={() =>
                                        change({
                                            result: value,
                                            waitlistNumber:
                                                value === 'WAITLIST'
                                                    ? record.waitlistNumber
                                                    : null,
                                        })
                                    }
                                />
                                <span className={styles.choiceText}>
                                    <strong>{label}</strong>
                                </span>
                            </label>
                        ))}
                    </div>
                    <div className={styles.resultHint}>
                        <Icon name="info" />
                        <p>
                            서류 제출 대상자 발표는 최종 선정 결과가 아니에요.
                        </p>
                    </div>
                    {record.result === 'WAITLIST' && (
                        <div className={styles.waitlist}>
                            <label>
                                예비 순번 <small>선택</small>
                                <input
                                    type="number"
                                    inputMode="numeric"
                                    min={1}
                                    max={999999}
                                    step={1}
                                    value={record.waitlistNumber ?? ''}
                                    onChange={(e) =>
                                        change({
                                            waitlistNumber:
                                                e.target.value === ''
                                                    ? null
                                                    : Number(e.target.value),
                                        })
                                    }
                                />
                            </label>
                            <p>
                                추가 안내로 선정이 확인되면 위 결과를 ‘선정’으로
                                바꿔 주세요.
                            </p>
                        </div>
                    )}
                    {record.result === 'NOT_SELECTED' && (
                        <p>
                            {record.documentsSubmitted ||
                            record.contractCompleted ||
                            record.movedIn
                                ? '기존 제출·계약·입주 기록은 남아 있어요. 현재 상황과 다른 기록은 아래에서 수정할 수 있어요.'
                                : '이번 결과를 보관하거나 다른 공고를 살펴볼 수 있어요.'}
                        </p>
                    )}
                </fieldset>
                {!!official.result.length && (
                    <section
                        className={styles.card}
                        aria-label="공식 발표 안내"
                    >
                        <h3>공식 발표 안내</h3>
                        {official.result.map((event) => (
                            <div key={event.id} data-result-card>
                                <ScheduleCard
                                    event={event}
                                    evidence={event.evidence}
                                />
                            </div>
                        ))}
                    </section>
                )}
                {steps
                    .filter(
                        (step) => official[step.key].length || record[step.key],
                    )
                    .map((step) => (
                        <fieldset
                            className={styles.card}
                            disabled={pending}
                            key={step.key}
                        >
                            <legend>{step.title}</legend>
                            <p>{step.hint}</p>
                            {official[step.key].map((event) => (
                                <div key={event.id} data-result-card>
                                    <ScheduleCard
                                        event={event}
                                        evidence={event.evidence}
                                    />
                                </div>
                            ))}
                            {!official[step.key].length && (
                                <p>
                                    이전 기록을 유지하고 있어요. 현재 안내에는
                                    이 단계의 일정이 없어요.
                                </p>
                            )}
                            <label className={styles.check}>
                                <input
                                    type="checkbox"
                                    checked={record[step.key]}
                                    onChange={(e) =>
                                        change({ [step.key]: e.target.checked })
                                    }
                                />
                                {step.label}
                            </label>
                            {step.key === 'documentsSubmitted' && (
                                <Link to={`/preparations/${p.id}/documents`}>
                                    내 서류 목록 보기
                                </Link>
                            )}
                        </fieldset>
                    ))}
                {p.guideState !== 'AVAILABLE' && (
                    <p className={styles.note}>
                        안내가 변경되어 기존 일정이 보이고 있어요. 최신 공식
                        안내를 확인해 주세요. 내 진행 기록은 계속 저장할 수
                        있어요.
                    </p>
                )}
                {update.error && <p role="alert">{update.error.message}</p>}
                {conflict && (
                    <div className={styles.card}>
                        <p>
                            다른 화면에서 기록이 바뀌었어요. 아래 버튼을 누르면
                            현재 입력을 취소하고 최신 기록을 불러와요.
                        </p>
                        <Button
                            type="button"
                            className="secondary"
                            disabled={pending}
                            onClick={() => reload.mutate()}
                        >
                            최신 기록 다시 불러오기
                        </Button>
                    </div>
                )}
                {reload.error && <p role="alert">{reload.error.message}</p>}
                {message && <p role="status">{message}</p>}
                <div className={styles.saveBar}>
                    <div className={styles.actions}>
                        <Button
                            disabled={
                                pending || conflict || (!dirty && !!p.followUp)
                            }
                            type="submit"
                        >
                            {update.isPending ? '저장 중…' : '진행 상황 저장'}
                        </Button>
                        {dirty && (
                            <Button
                                className="secondary"
                                disabled={pending}
                                type="button"
                                onClick={() => {
                                    setDraft(null);
                                    update.reset();
                                }}
                            >
                                변경 취소
                            </Button>
                        )}
                    </div>
                </div>
            </form>
        </div>
    );
}
