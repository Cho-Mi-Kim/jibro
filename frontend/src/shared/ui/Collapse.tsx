import { useEffect, useState, type ReactNode } from 'react';
import styles from './Collapse.module.css';

/** Keep content mounted until the shared closing transition completes. */
export function Collapse({
    open,
    children,
    className,
}: {
    open: boolean;
    children: ReactNode;
    className?: string;
}) {
    const [present, setPresent] = useState(open);
    if (open && !present) setPresent(true);
    useEffect(() => {
        if (open) return;
        const duration =
            parseFloat(
                getComputedStyle(document.documentElement).getPropertyValue(
                    '--motion-duration',
                ),
            ) || 750;
        const timer = window.setTimeout(() => setPresent(false), duration);
        return () => window.clearTimeout(timer);
    }, [open]);
    if (!present) return null;
    return (
        <div
            className={`${styles.collapse} ${className ?? ''}`}
            data-open={open}
            inert={!open}
            aria-hidden={!open}
        >
            <div className={styles.content}>{children}</div>
        </div>
    );
}
