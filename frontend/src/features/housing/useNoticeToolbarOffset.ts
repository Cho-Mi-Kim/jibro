import { useLayoutEffect, type RefObject } from 'react';

export function useNoticeToolbarOffset(ref: RefObject<HTMLElement | null>) {
    useLayoutEffect(() => {
        const toolbar = ref.current;
        const header = document.querySelector('[data-notice-header]');
        if (!toolbar || !header) return;
        const update = () => {
            toolbar.style.setProperty(
                '--notice-header-height',
                `${header.getBoundingClientRect().height}px`,
            );
        };
        update();
        const observer = new ResizeObserver(update);
        observer.observe(header);
        return () => observer.disconnect();
    }, [ref]);
}
