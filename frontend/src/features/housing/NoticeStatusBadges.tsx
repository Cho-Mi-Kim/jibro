import { useEffect, useRef, useState } from 'react';
import { Icon } from '../../shared/ui/Icon';
import { Status } from '../../shared/ui/Status';
import { applicationStatuses, guidanceStatus } from './noticeLabels';
import { effectiveNoticeStatus } from './noticeLifecycle';
import type { Notice } from './types';
import styles from './NoticeFacts.module.css';

type StatusSource = Pick<
    Notice,
    'status' | 'lifecycle' | 'guideState' | 'reviewIssues'
> & { partialGuidance?: boolean };
type Direction = 'left' | 'right';

export function ApplicationBadge({ notice }: { notice: StatusSource }) {
    const { label, icon, tone } =
        applicationStatuses[effectiveNoticeStatus(notice)];
    return (
        <Status tone={tone}>
            <Icon name={icon} />
            {label}
        </Status>
    );
}

export function GuideBadge({
    notice,
    direction = 'right',
}: {
    notice: StatusSource;
    direction?: Direction;
}) {
    const [expanded, setExpanded] = useState(false);
    const badgeRef = useRef<HTMLButtonElement>(null);
    useEffect(() => {
        if (!expanded) return;
        const closeOutside = (event: PointerEvent) => {
            if (
                event.target instanceof Node &&
                !badgeRef.current?.contains(event.target)
            ) {
                setExpanded(false);
            }
        };
        const closeOnEscape = (event: KeyboardEvent) => {
            if (event.key === 'Escape') setExpanded(false);
        };
        document.addEventListener('pointerdown', closeOutside);
        document.addEventListener('keydown', closeOnEscape);
        return () => {
            document.removeEventListener('pointerdown', closeOutside);
            document.removeEventListener('keydown', closeOnEscape);
        };
    }, [expanded]);
    const { label, icon, tone } = guidanceStatus(notice);
    return (
        <button
            ref={badgeRef}
            type="button"
            className={styles['guide-toggle']}
            data-tone={tone}
            data-direction={direction}
            aria-label={label}
            aria-expanded={expanded}
            onClick={(event) => {
                event.stopPropagation();
                setExpanded((value) => !value);
            }}
        >
            <span className={styles['guide-indicator']} data-tone={tone}>
                <Icon name={icon} />
            </span>
            <span className={styles['guide-label']} aria-hidden={!expanded}>
                {label}
            </span>
        </button>
    );
}

export function NoticeStatusBadges({
    notice,
    className = styles.statusRow,
    direction,
}: {
    notice: StatusSource;
    className?: string;
    direction?: Direction;
}) {
    return (
        <div className={className}>
            <ApplicationBadge notice={notice} />
            <GuideBadge notice={notice} direction={direction} />
        </div>
    );
}
