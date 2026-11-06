import { cx } from '../../shared/styles/classes';
import { useLayoutEffect, useRef, useState } from 'react';
import { useUnsavedChanges } from '../../shared/ui/useUnsavedChanges';
import { useMutation } from '@tanstack/react-query';
import { api } from '../housing/api';
import type {
    DocumentItem,
    DocumentState,
    Preparation,
} from '../housing/types';
import { Button, EvidenceLink } from '../../shared/ui/ui';
import { safeUrl } from '../../shared/ui/helpers';
import { Disc, OfficialLink, Status } from '../../shared/ui/HousingUI';
import { Icon } from '../../shared/ui/Icon';
import { useSaved } from './usePreparation';
const stateNames: Record<DocumentState, string> = {
    UNCHECKED: '미확인',
    NEEDED: '발급 필요',
    IN_PROGRESS: '준비 중',
    READY: '준비 완료',
    RECHECK: '재확인',
};
export function DocumentEditor({
    p,
    d,
    active,
    onSelect,
}: {
    p: Preparation;
    d: DocumentItem;
    active: boolean;
    onSelect: () => void;
}) {
    const itemRef = useRef<HTMLDivElement>(null);
    const scrollOnOpen = useRef(false);
    useLayoutEffect(() => {
        if (!active || !scrollOnOpen.current) return;
        scrollOnOpen.current = false;
        const item = itemRef.current;
        const panel = item?.closest<HTMLElement>('[data-preparation-panel]');
        if (!item || !panel) return;
        panel.scrollTo({
            top:
                panel.scrollTop +
                item.getBoundingClientRect().top -
                panel.getBoundingClientRect().top,
            behavior: window.matchMedia('(prefers-reduced-motion: reduce)')
                .matches
                ? 'instant'
                : 'smooth',
        });
    }, [active]);
    const [draftState, setState] = useState<DocumentState | null>(null);
    const [draftNote, setNote] = useState<string | null>(null);
    const state = draftState ?? d.state;
    const note = draftNote ?? d.note;
    useUnsavedChanges(state !== d.state || note !== d.note);
    const saved = useSaved();
    const mutation = useMutation({
        mutationFn: (ready: boolean) =>
            api.document(p.id, d.id, p.revision, ready ? 'READY' : state, note),
        onSuccess: (next) => {
            saved(next);
            setState(null);
            setNote(null);
        },
    });
    const unavailable =
        p.guideState !== 'AVAILABLE' ||
        d.necessity === 'UNKNOWN' ||
        d.necessity === 'NOT_APPLICABLE';
    const necessity = {
        REQUIRED: '공통 필수',
        CONDITIONAL: '해당 조건부',
        UNKNOWN: '해당 여부 미확인',
        NOT_APPLICABLE: '해당 없음',
    }[d.necessity];
    return (
        <div ref={itemRef} className={cx('document-item')} data-active={active}>
            <button
                className={cx('document-select')}
                aria-expanded={active}
                aria-controls={'document-' + d.id}
                onClick={() => {
                    scrollOnOpen.current = !active;
                    onSelect();
                }}
            >
                <Disc
                    name="file"
                    tone={d.state === 'READY' ? 'green' : 'amber'}
                />
                <strong>{d.title}</strong>
                <span className={cx('necessity-label')}>{necessity}</span>
                <Status
                    tone={
                        d.state === 'READY'
                            ? 'green'
                            : d.state === 'NEEDED'
                              ? 'red'
                              : 'gray'
                    }
                >
                    {stateNames[d.state]}
                </Status>
                <Icon name="arrow" />
            </button>
            <section
                id={'document-' + d.id}
                className={cx('document-editor')}
                hidden={!active}
            >
                <header>
                    <Disc name="file" tone="amber" />
                    <div>
                        <h2>{d.title}</h2>
                        <Status tone="amber">{necessity}</Status>{' '}
                        <Status>{stateNames[d.state]}</Status>
                    </div>
                </header>
                <dl className={cx('document-facts')}>
                    <dt>발급처</dt>
                    <dd>
                        {safeUrl(d.issueUrl) ? (
                            <OfficialLink url={d.issueUrl} className="">
                                {d.issuer}
                            </OfficialLink>
                        ) : (
                            d.issuer
                        )}
                    </dd>
                    <dt>발급 기준</dt>
                    <dd>{d.method}</dd>
                </dl>
                <EvidenceLink evidence={d.evidence} />
                <Button
                    className="wide"
                    disabled={
                        mutation.isPending || unavailable || d.state === 'READY'
                    }
                    onClick={() => mutation.mutate(true)}
                >
                    {d.state === 'READY' && <Icon name="check" />}
                    {mutation.isPending ? '저장 중…' : '준비 완료'}
                </Button>
                {mutation.error && <p role="alert">{mutation.error.message}</p>}
                {mutation.isSuccess && (
                    <p role="status">이 서류의 준비 기록을 저장했어요.</p>
                )}
                <details className={cx('document-options')}>
                    <summary>
                        상태·메모 수정 <Icon name="down" />
                    </summary>
                    <label>
                        준비 상태
                        <select
                            value={state}
                            disabled={mutation.isPending || unavailable}
                            onChange={(e) =>
                                setState(e.target.value as DocumentState)
                            }
                        >
                            {Object.entries(stateNames).map(([key, label]) => (
                                <option key={key} value={key}>
                                    {label}
                                </option>
                            ))}
                        </select>
                    </label>
                    <label>
                        메모 (개인정보·증빙 내용 입력 금지)
                        <textarea
                            maxLength={500}
                            disabled={mutation.isPending || unavailable}
                            value={note}
                            onChange={(e) => setNote(e.target.value)}
                        />
                    </label>
                    <Button
                        className="secondary wide"
                        disabled={mutation.isPending || unavailable}
                        onClick={() => mutation.mutate(false)}
                    >
                        변경사항 저장
                    </Button>
                </details>
            </section>
        </div>
    );
}
