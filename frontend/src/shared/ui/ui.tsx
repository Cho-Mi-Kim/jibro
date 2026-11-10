import { Icon } from './Icon';
import { BrickHouse } from './BrickHouse';
import { cx } from '../styles/classes';
import { safeUrl } from './helpers';
import { type ButtonHTMLAttributes, type ReactNode } from 'react';
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
    backTo,
    title,
    children,
}: {
    eyebrow?: string;
    backTo?: string;
    title: string;
    children?: ReactNode;
}) {
    return (
        <header className={cx('page-heading')}>
            <span className={cx('eyebrow')}>{eyebrow}</span>
            <div className={cx('heading-title')}>
                {backTo && (
                    <Link
                        className={cx('mobile-back')}
                        to={backTo}
                        aria-label="이전 화면으로"
                    >
                        <Icon name="back" />
                    </Link>
                )}
                <h1>{title}</h1>
            </div>
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
export function EvidenceLink({
    evidence,
    className = '',
    checkedAtOnNewLine = false,
}: {
    evidence: Evidence;
    className?: string;
    checkedAtOnNewLine?: boolean;
}) {
    return (
        <div className={`${cx('evidence')} ${className}`}>
            <span>
                {safeUrl(evidence.url) ? (
                    <a href={evidence.url!} target="_blank" rel="noreferrer">
                        {evidence.label} ↗
                    </a>
                ) : (
                    evidence.label
                )}
                {checkedAtOnNewLine ? <br /> : ' · '}
                {'확인 '}
                {evidence.checkedAt}
            </span>
            {!safeUrl(evidence.url) && <span>원문 경로 확인 필요</span>}
        </div>
    );
}
export function InlineExplanation({
    label,
    children,
}: {
    label: string;
    children: ReactNode;
}) {
    return (
        <details className={cx('progress-explanation')}>
            <summary aria-label={label}>
                <Icon name="down" />
            </summary>
            {children}
        </details>
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
            </div>
            <div className={cx('house-picture')}>
                <BrickHouse progress={progress} stage={stage} />
            </div>
            <InlineExplanation label="준비도 설명">
                <small>
                    준비도는 기록한 준비 작업의 진행률이며,
                    <br />
                    신청 자격이나 선정 가능성을 의미하지 않습니다.
                </small>
            </InlineExplanation>
        </div>
    );
}
