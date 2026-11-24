const activeScrolls = new WeakMap<Window | HTMLElement, () => void>();

/** Shared duration follows the site motion token. Returns a cancellation function. */
export function animateScroll(
    target: Window | HTMLElement,
    top: number | (() => number),
    reduced = false,
    onFinish?: () => void,
) {
    activeScrolls.get(target)?.();
    const start =
        target === window ? window.scrollY : (target as HTMLElement).scrollTop;
    const duration =
        parseFloat(
            getComputedStyle(document.documentElement).getPropertyValue(
                '--motion-duration',
            ),
        ) || 750;
    let frame = 0;
    let stopped = false;
    let startedAt: number | undefined;
    const stop = () => {
        if (stopped) return;
        stopped = true;
        cancelAnimationFrame(frame);
        target.removeEventListener('wheel', stop);
        target.removeEventListener('touchstart', stop);
        if (activeScrolls.get(target) === stop) activeScrolls.delete(target);
        onFinish?.();
    };
    if (reduced) {
        target.scrollTo({
            top: typeof top === 'function' ? top() : top,
            behavior: 'instant',
        });
        stop();
        return stop;
    }
    const step = (time: number) => {
        startedAt ??= time;
        const progress = Math.min(1, (time - startedAt) / duration);
        const eased = progress * progress * (3 - 2 * progress);
        target.scrollTo({
            top:
                start +
                ((typeof top === 'function' ? top() : top) - start) * eased,
            behavior: 'instant',
        });
        if (progress < 1) frame = requestAnimationFrame(step);
        else stop();
    };
    target.addEventListener('wheel', stop, { passive: true });
    target.addEventListener('touchstart', stop, { passive: true });
    activeScrolls.set(target, stop);
    frame = requestAnimationFrame(step);
    return stop;
}
