import { Collapse } from '../../shared/ui/Collapse';
import { effectiveNoticeStatus } from './noticeLifecycle';
import { useRef } from 'react';

import { Bookmark } from '../member/SavedNotices';
import { NoticePreview } from './NoticePreview';
import card from './NoticeCard.module.css';
import { NoticeCardDeadline } from './NoticeCardDeadline';

import { useLocation } from 'react-router';
import { GuideBadge } from './NoticeFacts';
import { Status } from '../../shared/ui/Status';

import type { NoticeListItem } from './types';
import { statusLabels } from './noticeLabels';
import { useNoticeCardScroll } from './useNoticeCardScroll';
import { AgencyMark } from './AgencyMark';
import { noticeListTitle } from './noticeDisplay';
export function NoticeCard({
    notice: n,
    expanded,
    toggle,
}: {
    notice: NoticeListItem;
    expanded: boolean;
    toggle: () => void;
}) {
    const { search } = useLocation();
    const ref = useRef<HTMLElement>(null);
    useNoticeCardScroll(ref, expanded);
    return (
        <article
            ref={ref}
            id={'notice-card-' + n.id}
            className={card.card}
            data-expanded={expanded}
        >
            <div className={card.agency}>
                <AgencyMark agency={n.agency} />
            </div>
            <div className={card.badges}>
                <Status
                    tone={effectiveNoticeStatus(n) === 'OPEN' ? 'red' : 'gray'}
                >
                    {statusLabels[effectiveNoticeStatus(n)]}
                </Status>
                <GuideBadge state={n.guideState} partial={n.partialGuidance} />
            </div>
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
                <p className={card.metadata}>
                    <span>{n.region}</span>
                    <span>{n.type}</span>
                </p>
                <div className={card.controls}>
                    <Bookmark id={n.id} />
                </div>
            </div>
            <Collapse open={expanded} className={card.previewMotion}>
                <NoticeCardDeadline notice={n} />
                <NoticePreview notice={n} search={search} />
            </Collapse>
        </article>
    );
}
