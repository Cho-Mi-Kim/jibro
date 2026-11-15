import type { MouseEvent } from 'react';
import { useScrollToCard } from '../../shared/ui/useScrollToCard';

export function usePreparationCardSelection<
    T extends HTMLElement = HTMLElement,
>() {
    const { cardRef, scrollToCard } = useScrollToCard<T>(
        '[data-preparation-panel]',
    );
    const onClick = (event: MouseEvent<T>) => {
        if (
            event.target instanceof Element &&
            event.target.closest(
                'a, button, input, select, textarea, [role="button"]',
            )
        )
            return;
        event.stopPropagation();
        scrollToCard();
    };
    return { cardRef, onClick, scrollToCard };
}
