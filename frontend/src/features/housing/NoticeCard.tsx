import { useRef } from 'react';

import { Bookmark } from '../member/SavedNotices';
import { NoticePreview } from './NoticePreview';
import card from './NoticeCard.module.css';
import { NoticeCardDeadline } from './NoticeCardDeadline';

import { useLocation } from 'react-router';
import { GuideBadge, Status } from '../../shared/ui/HousingUI';

import type { Notice } from './types';
import { statusLabels } from './noticeLabels';
import { useNoticeCardScroll } from './useNoticeCardScroll';
export function NoticeCard({
    notice: n,
    expanded,
    toggle,
}: {
    notice: Notice;
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
            <div className={card.badges}>
                <Status tone={n.status === 'OPEN' ? 'red' : 'gray'}>
                    {statusLabels[n.status]}
                </Status>
                <GuideBadge state={n.guideState} />
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
                        <span>{n.title}</span>
                    </button>
                </h3>
                <p className={card.metadata}>
                    <span>{n.agency}</span>
                    <span>{n.region}</span>
                    <span>{n.type}</span>
                </p>
                <div className={card.controls}>
                    <Bookmark id={n.id} />
                </div>
            </div>
            {expanded && <NoticeCardDeadline notice={n} />}
            {expanded && <NoticePreview id={n.id} search={search} />}
        </article>
    );
}
