import type { MouseEvent } from 'react';
import { useNoticeDetailScroll } from './useNoticeDetailScroll';

import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';

export function useNoticeDetailSelection<
    T extends HTMLElement = HTMLElement,
>() {
    const { cardRef, scrollToCard } = useNoticeDetailScroll<T>();
    const onClick = (event: MouseEvent<T>) =>
        handleDisclosureCardClick(event, scrollToCard);
    return { cardRef, onClick };
}
