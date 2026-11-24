import type { MouseEvent } from 'react';
import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';
import { useScrollToCard } from '../../shared/ui/useScrollToCard';

export function usePreparationCardSelection<
    T extends HTMLElement = HTMLElement,
>(toggleDisclosure?: () => void, resolveTarget?: (card: T) => HTMLElement) {
    const { cardRef, scrollToCard } = useScrollToCard<T>(
        '[data-preparation-panel]',
        resolveTarget,
    );
    const onClick = (event: MouseEvent<T>) =>
        handleDisclosureCardClick(event, scrollToCard, toggleDisclosure);
    return { cardRef, onClick, scrollToCard };
}
