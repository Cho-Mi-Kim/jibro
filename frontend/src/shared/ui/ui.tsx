import { Icon } from './Icon';
import { cx } from '../styles/classes';
import {
    type ButtonHTMLAttributes,
    type HTMLAttributes,
    type ReactNode,
} from 'react';
import { ActionButton } from '@seed-design/react';
import { Link } from 'react-router';
export function Button(
    props: Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'color'>,
) {
    return (
        <ActionButton
            {...props}
            className={cx('primary-button ' + (props.className ?? ''))}
        />
    );
}
export function Heading({
    eyebrow,
    backTo,
    backState,
    title,
    children,
}: {
    eyebrow?: string;
    backTo?: string;
    backState?: unknown;
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
                        state={backState}
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
export function Card({
    children,
    onClick,
}: {
    children: ReactNode;
    onClick?: HTMLAttributes<HTMLElement>['onClick'];
}) {
    return (
        <section className={cx('card')} onClick={onClick}>
            {children}
        </section>
    );
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
export function InlineExplanation({
    label,
    children,
    onToggle,
}: {
    label: string;
    children: ReactNode;
    onToggle?: (open: boolean) => void;
}) {
    return (
        <details
            className={cx('progress-explanation')}
            onToggle={(event) => onToggle?.(event.currentTarget.open)}
        >
            <summary aria-label={label}>
                <Icon name="down" />
            </summary>
            {children}
        </details>
    );
}
