import { useEffect, useLayoutEffect, useRef } from 'react';
import { animateScroll } from './animateScroll';
import { prefersReducedMotion } from './motionPreferences';

function releaseSpace(element: HTMLElement | null) {
    element?.style.removeProperty('min-height');
    element?.style.removeProperty('overflow-anchor');
}

/** Keep the old list height until the requested page can be aligned below its sticky controls. */
export function usePageScroll({
    page,
    ready,
    scope,
    headerSelector,
    anchorSelector,
    extraSpace,
}: {
    page: number;
    ready: boolean;
    scope: string;
    headerSelector: string;
    anchorSelector?: string;
    extraSpace?: () => number;
}) {
    const contentRef = useRef<HTMLElement>(null);
    const pendingPage = useRef<number | null>(null);
    const cancelScroll = useRef<() => void>(() => {});
    const frame = useRef(0);

    useEffect(() => {
        const content = contentRef.current;
        return () => {
            pendingPage.current = null;
            cancelAnimationFrame(frame.current);
            cancelScroll.current();
            releaseSpace(content);
        };
    }, [scope]);

    useLayoutEffect(() => {
        if (pendingPage.current !== page || !ready) return;
        pendingPage.current = null;
        frame.current = requestAnimationFrame(() => {
            const content = contentRef.current;
            if (!content) return;
            cancelScroll.current = animateScroll(
                window,
                () => {
                    const anchor =
                        (anchorSelector &&
                            content.querySelector(anchorSelector)) ||
                        content;
                    const offset = Array.from(
                        document.querySelectorAll(headerSelector),
                    ).reduce(
                        (height, header) =>
                            height + header.getBoundingClientRect().height,
                        8,
                    );
                    return Math.max(
                        0,
                        anchor.getBoundingClientRect().top +
                            window.scrollY -
                            offset,
                    );
                },
                prefersReducedMotion(),
                () => releaseSpace(content),
            );
        });
        return () => {
            cancelAnimationFrame(frame.current);
            cancelScroll.current();
        };
    }, [page, ready, headerSelector, anchorSelector]);

    function preparePageChange(nextPage: number) {
        cancelAnimationFrame(frame.current);
        cancelScroll.current();
        const content = contentRef.current;
        if (content) {
            content.style.overflowAnchor = 'none';
            content.style.minHeight = `${Math.ceil(content.getBoundingClientRect().height + (extraSpace?.() ?? 0))}px`;
        }
        pendingPage.current = nextPage;
    }

    return { contentRef, preparePageChange };
}
