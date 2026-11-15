import { cx } from '../styles/classes';
import { Icon } from './Icon';
import { evidenceHref } from './helpers';
export function OfficialLink({
    url,
    children,
    className,
    showIcon = true,
}: {
    url?: string | null;
    children: React.ReactNode;
    className?: string;
    showIcon?: boolean;
}) {
    const href = evidenceHref(url);
    return href ? (
        <a
            className={cx(className ?? 'link-button outline-button')}
            href={href}
            target="_blank"
            rel="noreferrer"
        >
            {children}
            {showIcon && <Icon name="external" />}
        </a>
    ) : (
        <span className={cx('muted')}>공식 링크 확인 필요</span>
    );
}
