import { BrickHouse } from './BrickHouse';
import { cx } from '../styles/classes';
import { safeUrl } from './helpers';
import {
    useEffect,
    useRef,
    useState,
    type ButtonHTMLAttributes,
    type ReactNode,
} from 'react';
import { ActionButton } from '@seed-design/react';
import { Link } from 'react-router';
import type { Evidence } from '../../features/housing/types';
export function Button(
    props: Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'color'>,
) {
    return (
        <ActionButton
            {...props}
            className={cx('button ' + (props.className ?? ''))}
        />
    );
}
export function Heading({
    eyebrow,
    title,
    children,
}: {
    eyebrow?: string;
    title: string;
    children?: ReactNode;
}) {
    return (
        <header className={cx('page-heading')}>
            <span className={cx('eyebrow')}>{eyebrow}</span>
            <h1>{title}</h1>
            {children && <p>{children}</p>}
        </header>
    );
}
export function Card({ children }: { children: ReactNode }) {
    return <section className={cx('card')}>{children}</section>;
}
export function State({
    error,
    loading,
    retry,
}: {
    error?: Error | null;
    loading?: boolean;
    retry?: () => void;
}) {
    if (loading)
        return (
            <div className={cx('state')} role="status">
                내용을 불러오고 있어요…
            </div>
        );
    if (error)
        return (
            <div className={cx('state')} role="alert">
                <h2>내용을 불러오지 못했어요</h2>
                <p>{error.message}</p>
                {retry && <Button onClick={retry}>다시 시도</Button>}{' '}
                <Link to="/login">로그인 확인</Link>
            </div>
        );
    return null;
}
export function Badge({ children }: { children: ReactNode }) {
    return <span className={cx('badge')}>{children}</span>;
}
export function EvidenceLink({ evidence }: { evidence: Evidence }) {
    return (
        <div className={cx('evidence')}>
            <span>
                {evidence.label} · 확인 {evidence.checkedAt}
            </span>
            {safeUrl(evidence.url) ? (
                <a href={evidence.url!} target="_blank" rel="noreferrer">
                    공식 근거 열기 ↗
                </a>
            ) : (
                <span>원문 경로 확인 필요</span>
            )}
        </div>
    );
}
export function Soon({ label }: { label: string }) {
    const [open, setOpen] = useState(false);
    const ref = useRef<HTMLDialogElement>(null);
    useEffect(() => {
        if (open) ref.current?.showModal();
        else ref.current?.close();
    }, [open]);
    return (
        <>
            <button
                type="button"
                className={cx('text-button')}
                onClick={() => setOpen(true)}
            >
                {label} <small>준비 중</small>
            </button>
            <dialog
                ref={ref}
                onCancel={() => setOpen(false)}
                onClose={() => setOpen(false)}
                aria-label={label}
            >
                <h2>{label}</h2>
                <p>아직 준비 중인 기능입니다.</p>
                <p>
                    현재는 공고의 공식 원문과 내 준비 목록을 확인할 수 있어요.
                </p>
                <Button onClick={() => setOpen(false)}>닫기</Button>
            </dialog>
        </>
    );
}
export function House({
    progress,
    stage,
}: {
    progress: number;
    stage: number;
}) {
    return (
        <div className={cx('house-row')}>
            <div>
                <span>전체 준비도</span>
                <strong className={cx('progress-number')}>{progress}%</strong>
                <progress max="100" value={progress} aria-label="준비도" />
                <small>
                    준비도는 기록한 준비 작업의 진행률이며,
                    <br />
                    신청 자격이나 선정 가능성을 의미하지 않습니다.
                </small>
            </div>
            <BrickHouse stage={stage} />
        </div>
    );
}
