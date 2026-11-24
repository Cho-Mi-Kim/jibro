import { useEffect, useRef } from 'react';
import { useScrollToCard } from './useScrollToCard';

export function useResultCardScroll(hostSelector: string) {
    const { cardRef, scrollToCard } = useScrollToCard(hostSelector);
    const resultsRef = useRef<HTMLDivElement>(null);
    useEffect(() => {
        const results = resultsRef.current;
        if (!results) return;
        function alignCard(event: Event) {
            const disclosure = event.target;
            if (!(disclosure instanceof HTMLDetailsElement)) return;
            const card = disclosure.closest<HTMLElement>('[data-result-card]');
            if (!card) return;
            cardRef.current = card;
            scrollToCard(disclosure);
        }
        // Native details toggle events reach the results container during capture.
        results.addEventListener('toggle', alignCard, true);
        return () => results.removeEventListener('toggle', alignCard, true);
    }, [cardRef, scrollToCard]);
    return resultsRef;
}
