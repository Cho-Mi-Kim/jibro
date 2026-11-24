import { animateScroll } from '../../shared/ui/animateScroll';
import { useEffect, type RefObject } from 'react';

/** Reserve only the missing scroll room, including while preview data is loading. */
export function useNoticeCardScroll(
    ref: RefObject<HTMLElement | null>,
    expanded: boolean,
    alignOnExpand = true,
) {
    useEffect(() => {
        const card = ref.current;
        const list = card?.closest<HTMLElement>('[data-notice-list]');
        if (!expanded || !card || !list) return;

        const header = document.querySelector('[data-notice-header]');
        const controls = document.querySelector('[data-notice-controls]');
        let frame = 0;
        let cancelScroll = () => {};
        const stopScroll = () => cancelScroll();
        const alignCard = () => {
            if (
                window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
            ) {
                card.scrollIntoView?.({ block: 'start', behavior: 'instant' });
            } else {
                cancelScroll = animateScroll(window, () => {
                    const offset =
                        (header?.getBoundingClientRect().height ?? 0) +
                        (controls?.getBoundingClientRect().height ?? 0) +
                        12;
                    return Math.max(
                        0,
                        card.getBoundingClientRect().top +
                            window.scrollY -
                            offset,
                    );
                });
            }
        };
        let alignOnOpen = alignOnExpand;
        const update = () => {
            frame = 0;
            const controlsHeight =
                controls?.getBoundingClientRect().height ?? 0;
            const offset =
                (header?.getBoundingClientRect().height ?? 0) +
                controlsHeight +
                12;
            card.style.scrollMarginTop = `${offset}px`;
            const target = Math.max(
                0,
                card.getBoundingClientRect().top + window.scrollY - offset,
            );
            const previousSpace =
                parseFloat(
                    list.style.getPropertyValue('--notice-scroll-space'),
                ) || 0;
            // Reserve the final room before the toolbar shrinks, so the browser
            // never clamps the scroll position during the collapse animation.
            const collapsingHeight =
                controls?.getAttribute('data-collapsed') === 'true'
                    ? controlsHeight
                    : 0;
            const available = Math.max(
                0,
                document.documentElement.scrollHeight -
                    previousSpace -
                    window.innerHeight -
                    collapsingHeight,
            );
            list.style.setProperty(
                '--notice-scroll-space',
                `${Math.ceil(Math.max(0, target - available))}px`,
            );
            if (alignOnOpen) {
                alignCard();
                alignOnOpen = false;
            }
        };
        const schedule = () => {
            if (!frame) frame = requestAnimationFrame(update);
        };
        const observer = new ResizeObserver(schedule);
        observer.observe(card);
        if (header) observer.observe(header);
        if (controls) observer.observe(controls);
        window.addEventListener('resize', schedule);
        window.addEventListener('wheel', stopScroll, { passive: true });
        window.addEventListener('touchstart', stopScroll, { passive: true });
        schedule();
        return () => {
            cancelAnimationFrame(frame);
            stopScroll();
            window.removeEventListener('wheel', stopScroll);
            window.removeEventListener('touchstart', stopScroll);
            observer.disconnect();
            window.removeEventListener('resize', schedule);
            list.style.removeProperty('--notice-scroll-space');
        };
    }, [expanded, ref, alignOnExpand]);
}
