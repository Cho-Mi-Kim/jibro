import { cx } from '../styles/classes';
export function Status({
    children,
    tone = 'gray',
}: {
    children: React.ReactNode;
    tone?: string;
}) {
    return (
        <span className={cx('status-badge')} data-tone={tone}>
            {children}
        </span>
    );
}
