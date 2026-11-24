import {
    createContext,
    useContext,
    useLayoutEffect,
    useRef,
    useState,
} from 'react';
import { useLocation } from 'react-router';

interface ListPosition {
    pathname: string;
    search: string;
    top: number;
    scrollSpace: string;
    previewId?: string;
    toolbarRevealed: boolean;
}

interface DetailPosition {
    pathname: string;
    search: string;
    state: ReturnType<typeof useLocation>['state'];
    top: number;
    scrollSpace: string;
    disclosures: Map<number, string>;
}
export interface Visits {
    lists: Map<string, ListPosition>;
    details: Map<string, DetailPosition>;
}

export const NoticeVisits = createContext<Visits | null>(null);

export function useSavedComparisonReturn() {
    return (
        '/saved' + (useContext(NoticeVisits)?.lists.get('/saved')?.search ?? '')
    );
}

export function useNoticeListPosition<T extends HTMLElement>(ready: boolean) {
    const { lists: visits } = useContext(NoticeVisits)!;
    const { pathname, search, key, state } = useLocation();
    const list = useRef<T>(null);
    const applied = useRef<ListPosition | null>(null);
    const [initial, setInitial] = useState(() => {
        const returnKey =
            pathname === '/compare' || pathname === '/saved/compare'
                ? state?.comparisonKey
                : state?.noticeListKey;
        const entry =
            visits.get(key) ??
            visits.get(returnKey) ??
            (pathname === '/saved' ? visits.get('/saved') : undefined);
        return entry?.pathname === pathname && entry.search === search
            ? entry
            : undefined;
    });
    const position = initial?.search === search ? initial : undefined;
    useLayoutEffect(() => {
        if (
            !position ||
            !ready ||
            !list.current ||
            applied.current === position
        )
            return;
        applied.current = position;
        if (list.current.querySelector('[data-expanded="true"]')) {
            list.current.style.setProperty(
                '--notice-scroll-space',
                position.scrollSpace,
            );
        }
        window.scrollTo({ top: position.top, behavior: 'instant' });
    }, [position, ready]);

    function remember(previewId?: string, toolbarRevealed = false) {
        const entry = {
            pathname,
            search,
            previewId,
            toolbarRevealed,
            top: window.scrollY,
            scrollSpace:
                list.current?.style.getPropertyValue('--notice-scroll-space') ??
                '',
        };
        visits.set(key, entry);
        if (pathname === '/saved') visits.set('/saved', entry);
    }
    return { list, position, remember, clear: () => setInitial(undefined) };
}

function disclosureLabel(element: HTMLDetailsElement) {
    const summary = element.querySelector(':scope > summary');
    return summary?.getAttribute('aria-label') ?? summary?.textContent ?? '';
}

function detailScrollTarget(host: HTMLElement) {
    return /auto|scroll/.test(getComputedStyle(host).overflowY) ? host : window;
}

export function useNoticeDetailPosition(ready: boolean) {
    const { details } = useContext(NoticeVisits)!;
    const location = useLocation();
    const host = useRef<HTMLDivElement>(null);
    const applied = useRef<DetailPosition | null>(null);
    const entry =
        details.get(location.key) ?? details.get(location.state?.detailKey);
    const position =
        entry?.pathname === location.pathname &&
        entry.search === location.search
            ? entry
            : undefined;
    useLayoutEffect(() => {
        const element = host.current;
        if (!ready || !element || !position || applied.current === position)
            return;
        applied.current = position;
        element.dataset.restoringDisclosures = '';
        element.querySelectorAll('details').forEach((item, index) => {
            if (position.disclosures.get(index) === disclosureLabel(item))
                item.open = true;
        });
        element.style.setProperty(
            '--summary-scroll-space',
            position.scrollSpace,
        );
        detailScrollTarget(element).scrollTo({
            top: position.top,
            behavior: 'instant',
        });
        delete element.dataset.restoringDisclosures;
    }, [ready, position]);
    function remember() {
        const element = host.current;
        if (!element) return;
        const disclosures = new Map<number, string>();
        element.querySelectorAll('details').forEach((item, index) => {
            if (item.open) disclosures.set(index, disclosureLabel(item));
        });
        details.set(location.key, {
            pathname: location.pathname,
            search: location.search,
            state: location.state,
            top:
                detailScrollTarget(element) === window
                    ? window.scrollY
                    : element.scrollTop,
            scrollSpace: element.style.getPropertyValue(
                '--summary-scroll-space',
            ),
            disclosures,
        });
    }
    return { host, position, remember };
}

export function useNoticeDetailReturn(noticeId?: string) {
    const visits = useContext(NoticeVisits);
    const origin = useLocation().state?.detailReturn;
    if (
        !visits ||
        typeof origin?.id !== 'string' ||
        typeof origin?.search !== 'string' ||
        (noticeId !== undefined && origin.id !== noticeId)
    )
        return;
    return {
        to: '/notices/' + encodeURIComponent(origin.id) + origin.search,
        state: {
            ...visits.details.get(origin.key)?.state,
            detailKey: origin.key,
        },
    };
}
