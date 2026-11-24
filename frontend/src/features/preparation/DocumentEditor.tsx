import { DocumentStateBadge } from './DocumentStateBadge';
import { documentStatePresentation } from './documentStatePresentation';
import { bindClasses } from '../../shared/styles/classes';
import { useEffect, useState } from 'react';
import { useUnsavedChanges } from '../../shared/ui/useUnsavedChanges';
import { useScrollToCard } from '../../shared/ui/useScrollToCard';
import { useMutation } from '@tanstack/react-query';
import { api } from '../housing/api';
import type {
    DocumentItem,
    DocumentState,
    Preparation,
} from '../housing/types';
import { Button } from '../../shared/ui/ui';
import { EvidenceLink } from '../housing/EvidenceLink';
import {
    documentNeedsReview,
    officialReviewLabel,
} from '../housing/reviewIssueSelectors';
import { Disc } from '../../shared/ui/Disc';
import { Status } from '../../shared/ui/Status';
import { Icon } from '../../shared/ui/Icon';
import { useSaved } from './usePreparation';
import { usePreparationCardSelection } from './usePreparationCardSelection';
import { documentSummary } from '../housing/documentSummary';
import { DocumentCard } from '../housing/DocumentCard';
import documentsClasses from './Documents.module.css';

const cx = bindClasses(documentsClasses);

function documentScrollTarget(card: HTMLDivElement) {
    const detail = card.querySelector<HTMLElement>(
        ':scope > section:not([hidden])',
    );
    // The desktop detail sits beside the list; mobile details stay in the card.
    return detail && getComputedStyle(detail).position === 'absolute'
        ? detail
        : card;
}

export function DocumentEditor({
    p,
    d,
    active,
    onSelect,
    onReady,
    scrollRequest = 0,
}: {
    p: Preparation;
    d: DocumentItem;
    active: boolean;
    onSelect: () => void;
    onReady?: (next: Preparation) => boolean;
    scrollRequest?: number;
}) {
    const { cardRef, onClick, scrollToCard } =
        usePreparationCardSelection<HTMLDivElement>(
            onSelect,
            documentScrollTarget,
        );
    const { cardRef: optionsRef, scrollToCard: scrollToOptions } =
        useScrollToCard<HTMLDetailsElement>('[data-preparation-panel]');
    useEffect(() => {
        if (scrollRequest > 0) scrollToCard();
    }, [scrollRequest, scrollToCard]);
    const [draftState, setState] = useState<DocumentState | null>(null);
    const [draftNote, setNote] = useState<string | null>(null);
    const state = draftState ?? d.state;
    const note = draftNote ?? d.note;
    useUnsavedChanges(state !== d.state || note !== d.note);
    const saved = useSaved();
    const mutation = useMutation({
        mutationFn: (nextState: DocumentState) =>
            api.document(p.id, d.id, p.revision, nextState, note),
        onSuccess: (next, nextState) => {
            saved(next);
            setState(null);
            setNote(null);
            const options = optionsRef.current;
            if (options?.open) {
                options.open = false;
            }
            if (nextState === 'READY' && d.state !== 'READY' && onReady?.(next))
                return;
            if (cardRef.current?.dataset.active === 'true') {
                onSelect();
                scrollToCard();
            }
        },
    });
    const reviewRequired = documentNeedsReview(d, p.reviewIssues);
    const quickState = d.state === 'READY' ? d.stateBeforeReady : 'READY';
    const unavailable =
        reviewRequired ||
        p.guideState !== 'AVAILABLE' ||
        d.necessity === 'UNKNOWN' ||
        d.necessity === 'NOT_APPLICABLE';
    const summary = documentSummary(d, p);
    const necessity = reviewRequired
        ? officialReviewLabel
        : {
              REQUIRED: '공통 필수',
              CONDITIONAL: '해당 조건부',
              UNKNOWN: '해당 여부 미확인',
              NOT_APPLICABLE: '해당 없음',
          }[d.necessity];
    return (
        <div
            ref={cardRef}
            onClick={onClick}
            className={cx('document-item')}
            data-active={active}
        >
            <DocumentCard
                title={d.title}
                summary={summary}
                editor={{
                    open: active,
                    toggle: () => {
                        onSelect();
                        scrollToCard();
                    },
                    className: cx('document-editor'),
                    status: (
                        <>
                            {active && (
                                <span className={cx('necessity-label')}>
                                    {necessity}
                                </span>
                            )}
                            <DocumentStateBadge state={d.state} />
                        </>
                    ),
                    header: (
                        <header>
                            <Disc name="file" tone="amber" />
                            <div>
                                <h2>{d.title}</h2>
                                <Status tone="amber">{necessity}</Status>{' '}
                                <DocumentStateBadge state={d.state} />
                            </div>
                        </header>
                    ),
                }}
            >
                {!summary.fullMethodShown && (
                    <dl className={cx('document-facts')}>
                        <dt>발급처</dt>
                        <dd>{d.issuer}</dd>
                        <dt>발급 기준</dt>
                        <dd>{d.method}</dd>
                    </dl>
                )}
                <EvidenceLink evidence={d.evidence} />

                {reviewRequired && (
                    <p>
                        공식 확인 전에는 이 서류를 준비 완료로 기록할 수 없어요.
                        기존 준비 상태와 메모는 보존됩니다.
                    </p>
                )}
                <Button
                    className="wide"
                    disabled={mutation.isPending || unavailable}
                    onClick={() => {
                        if (quickState) {
                            mutation.mutate(quickState);
                        } else if (optionsRef.current) {
                            optionsRef.current.open = true;
                            optionsRef.current
                                .querySelector('select')
                                ?.focus({ preventScroll: true });
                        }
                    }}
                >
                    {mutation.isPending
                        ? '저장 중…'
                        : d.state === 'READY'
                          ? documentStatePresentation.READY.label + ' 취소'
                          : documentStatePresentation.READY.label}
                </Button>
                {mutation.error && <p role="alert">{mutation.error.message}</p>}
                {mutation.isSuccess && (
                    <p role="status">이 서류의 준비 기록을 저장했어요.</p>
                )}
                <details
                    ref={optionsRef}
                    className={cx('document-options')}
                    onToggle={({ currentTarget }) => {
                        if (!active) return;
                        if (currentTarget.open) scrollToOptions(currentTarget);
                        else scrollToCard(currentTarget);
                    }}
                >
                    <summary onClick={(event) => event.stopPropagation()}>
                        상태·메모 수정 <Icon name="down" />
                    </summary>
                    {!quickState && (
                        <p>
                            완료 전 상태 기록이 없어요. 돌아갈 상태를 선택하고
                            저장해 주세요.
                        </p>
                    )}
                    <label>
                        준비 상태
                        <select
                            value={state}
                            disabled={mutation.isPending || unavailable}
                            onChange={(e) =>
                                setState(e.target.value as DocumentState)
                            }
                        >
                            {Object.entries(documentStatePresentation).map(
                                ([key, { label }]) => (
                                    <option key={key} value={key}>
                                        {label}
                                    </option>
                                ),
                            )}
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
                        onClick={() => mutation.mutate(state)}
                    >
                        변경사항 저장
                    </Button>
                </details>
            </DocumentCard>
        </div>
    );
}
