import type { MouseEvent } from 'react';
import { useNoticeDetailScroll } from './useNoticeDetailScroll';

export function useNoticeDetailSelection<
    T extends HTMLElement = HTMLElement,
>() {
    const { cardRef, scrollToCard } = useNoticeDetailScroll<T>();
    const onClick = (event: MouseEvent<T>) => {
        const target = event.target;
        if (
            target instanceof Element &&
            target.closest(
                'a, button, input, select, textarea, [role="button"]',
            )
        )
            return;
        scrollToCard();
    };
    return { cardRef, onClick };
}
