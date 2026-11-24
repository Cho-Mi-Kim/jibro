import type { MouseEvent } from 'react';

export function handleDisclosureCardClick(
    event: MouseEvent<HTMLElement>,
    scrollToCard?: () => void,
    toggleDisclosure?: () => void,
) {
    const target = event.target;
    const card = event.currentTarget;
    if (
        event.defaultPrevented ||
        !(target instanceof Element) ||
        target.closest(
            'a, button, label, input, select, textarea, dialog, [role="button"], [role="link"], [contenteditable="true"]',
        ) ||
        window.getSelection()?.type === 'Range'
    )
        return;

    // A summary already toggles its own disclosure via native mouse and keyboard handling.
    if (!target.closest('summary')) {
        if (toggleDisclosure) {
            toggleDisclosure();
        } else {
            const disclosure =
                card instanceof HTMLDetailsElement
                    ? card
                    : card.querySelector<HTMLDetailsElement>(
                          ':scope > details, details[data-card-disclosure]',
                      );
            if (disclosure) disclosure.open = !disclosure.open;
        }
    }
    event.stopPropagation();
    scrollToCard?.();
}
