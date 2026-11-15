export function prefersReducedMotion() {
    return (
        document.documentElement.dataset.reduceMotion === 'true' ||
        !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    );
}
