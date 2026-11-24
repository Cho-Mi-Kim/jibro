import { useCallback, useEffect, useId, useRef } from 'react';
import { prefersReducedMotion } from './motionPreferences';
import { animateScroll } from './animateScroll';

/** Align selected content within the detail page's actual scroll container. */
export function useScrollToCard<T extends HTMLElement = HTMLElement>(
    hostSelector: string,
    resolveTarget?: (card: T) => HTMLElement,
) {
    const cardRef = useRef<T>(null);
    const hostRef = useRef<HTMLElement>(null);
    const owner = useId();
    const cancel = useRef<() => void>(() => {});
    const frame = useRef(0);
    const clearSpace = useCallback(() => {
        const host = hostRef.current;
        if (host?.dataset.summaryScrollOwner === owner) {
            host.style.removeProperty('--summary-scroll-space');
            delete host.dataset.summaryScrollOwner;
        }
    }, [owner]);
    useEffect(
        () => () => {
            cancelAnimationFrame(frame.current);
            cancel.current();
            clearSpace();
        },
        [clearSpace],
    );
    const scrollToCard = useCallback(
        (disclosure?: HTMLDetailsElement) => {
            cancelAnimationFrame(frame.current);
            cancel.current();
            frame.current = requestAnimationFrame(() => {
                const root = cardRef.current;
                const card = root && (resolveTarget?.(root) ?? root);
                const host = card?.closest<HTMLElement>(hostSelector);
                if (!card || !host) return;
                const isPanel = /auto|scroll/.test(
                    getComputedStyle(host).overflowY,
                );
                const target = isPanel ? host : window;
                const position = () =>
                    Math.max(
                        0,
                        isPanel
                            ? host.scrollTop +
                                  card.getBoundingClientRect().top -
                                  host.getBoundingClientRect().top -
                                  12
                            : window.scrollY +
                                  card.getBoundingClientRect().top -
                                  12,
                    );
                const previous =
                    parseFloat(
                        host.style.getPropertyValue('--summary-scroll-space'),
                    ) || 0;
                const maxScroll = isPanel
                    ? host.scrollHeight - host.clientHeight - previous
                    : document.documentElement.scrollHeight -
                      window.innerHeight -
                      previous;
                // Keep the title reachable after a closing answer finishes shrinking.
                const summary = disclosure?.querySelector('summary');
                const closingSpace =
                    disclosure && !disclosure.open && summary
                        ? Math.max(
                              0,
                              disclosure.getBoundingClientRect().bottom -
                                  summary.getBoundingClientRect().bottom,
                          )
                        : 0;
                host.style.setProperty(
                    '--summary-scroll-space',
                    `${Math.ceil(Math.max(0, position() - maxScroll + closingSpace))}px`,
                );
                host.dataset.summaryScrollOwner = owner;
                hostRef.current = host;
                cancel.current = animateScroll(
                    target,
                    position,
                    prefersReducedMotion(),
                );
            });
        },
        [owner, hostSelector, resolveTarget],
    );
    return { cardRef, scrollToCard };
}
