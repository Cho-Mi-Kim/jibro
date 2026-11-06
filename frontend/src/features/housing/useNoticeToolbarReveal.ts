import { useEffect, useState } from 'react';

function isControlInteraction(target: EventTarget | null) {
    return (
        target instanceof Element &&
        target.closest(
            'input, textarea, select, [contenteditable="true"], dialog, [role="dialog"], details[open]',
        )
    );
}

/** Follow the user's scroll direction, never the card's automatic scroll. */
export function useNoticeToolbarReveal(expansion: object | null) {
    const [revealed, setRevealed] = useState<object | null>(null);
    const collapsed = expansion !== null && revealed !== expansion;

    useEffect(() => {
        if (!expansion) return;

        const reveal = () => setRevealed(expansion);
        const collapse = () => setRevealed(null);
        const onWheel = (event: WheelEvent) => {
            if (
                !event.ctrlKey &&
                !isControlInteraction(event.target) &&
                Math.abs(event.deltaY) > 1 &&
                Math.abs(event.deltaY) > Math.abs(event.deltaX)
            )
                (event.deltaY < 0 ? reveal : collapse)();
        };
        let touchStart: { x: number; y: number } | null = null;
        const onTouchStart = (event: TouchEvent) => {
            const touch =
                event.touches.length === 1 &&
                !isControlInteraction(event.target)
                    ? event.touches[0]
                    : null;
            touchStart = touch ? { x: touch.clientX, y: touch.clientY } : null;
        };
        const onTouchMove = (event: TouchEvent) => {
            if (!touchStart || event.touches.length !== 1) return;
            const touch = event.touches[0];
            const distance = touch.clientY - touchStart.y;
            if (
                Math.abs(distance) > 8 &&
                Math.abs(distance) > Math.abs(touch.clientX - touchStart.x)
            ) {
                (distance > 0 ? reveal : collapse)();
                touchStart = { x: touch.clientX, y: touch.clientY };
            }
        };
        const onKeyDown = (event: KeyboardEvent) => {
            if (
                event.defaultPrevented ||
                event.altKey ||
                event.ctrlKey ||
                event.metaKey
            )
                return;
            const target = event.target;
            if (isControlInteraction(target)) return;
            // Space activates the focused control instead of scrolling there.
            if (
                event.key === ' ' &&
                target instanceof Element &&
                target.closest('button, a, summary')
            )
                return;
            if (
                ['ArrowUp', 'PageUp', 'Home'].includes(event.key) ||
                (event.shiftKey && [' ', 'Tab'].includes(event.key))
            )
                reveal();
            else if (['ArrowDown', 'PageDown', 'End', ' '].includes(event.key))
                collapse();
        };
        window.addEventListener('wheel', onWheel, { passive: true });
        window.addEventListener('touchstart', onTouchStart, { passive: true });
        window.addEventListener('touchmove', onTouchMove, { passive: true });
        window.addEventListener('keydown', onKeyDown);
        return () => {
            window.removeEventListener('wheel', onWheel);
            window.removeEventListener('touchstart', onTouchStart);
            window.removeEventListener('touchmove', onTouchMove);
            window.removeEventListener('keydown', onKeyDown);
        };
    }, [expansion]);

    return collapsed;
}
