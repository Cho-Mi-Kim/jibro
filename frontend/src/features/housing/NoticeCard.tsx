import { NoticeStatusBadges } from './NoticeStatusBadges';
import { noticeHousingSummary } from './noticeHousingFacts';
import { Collapse } from '../../shared/ui/Collapse';
import { useRef } from 'react';

import { Bookmark } from '../member/SavedNotices';
import { NoticePreview } from './NoticePreview';
import card from './NoticeCard.module.css';
import { NoticeCardDeadline } from './NoticeCardDeadline';

import type { NoticeListItem } from './types';
import { useNoticeCardScroll } from './useNoticeCardScroll';
import { AgencyMark } from './AgencyMark';
import { noticeListTitle } from './noticeDisplay';
export function NoticeCard({
    notice: n,
    expanded,
    toggle,
    showCollapsedDeadline = false,
    restoreExpanded = false,
    onOpenDetail,
}: {
    notice: NoticeListItem;
    expanded: boolean;
    toggle: () => void;
    showCollapsedDeadline?: boolean;
    restoreExpanded?: boolean;
    onOpenDetail?: () => void;
}) {
    const ref = useRef<HTMLElement>(null);
    useNoticeCardScroll(ref, expanded, !restoreExpanded);
    return (
        <article
            ref={ref}
            id={'notice-card-' + n.id}
            className={card.card}
            data-expanded={expanded}
            data-restored={restoreExpanded || undefined}
        >
            <div className={card.agency}>
                <AgencyMark agency={n.agency} />
            </div>
            <NoticeStatusBadges
                notice={n}
                className={card.badges}
                direction="left"
            />
            <div className={card.main}>
                <h3 className={card.title}>
                    <button
                        type="button"
                        aria-label={n.title}
                        aria-expanded={expanded}
                        aria-controls={
                            expanded ? 'notice-preview-' + n.id : undefined
                        }
                        onClick={toggle}
                    >
                        <span>{noticeListTitle(n.title)}</span>
                    </button>
                </h3>
                <p className={card.metadata}>{noticeHousingSummary(n)}</p>
                <div className={card.controls}>
                    <Bookmark id={n.id} />
                </div>
            </div>
            {showCollapsedDeadline && (
                <NoticeCardDeadline notice={n} includeDate={!expanded} />
            )}
            <Collapse open={expanded} className={card.previewMotion}>
                {!showCollapsedDeadline && <NoticeCardDeadline notice={n} />}
                <NoticePreview notice={n} onOpenDetail={onOpenDetail} />
            </Collapse>
        </article>
    );
}
