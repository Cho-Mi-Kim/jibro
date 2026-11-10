// Only an explicit start action may continue automatically after authentication.
const key = 'jibro.notice-start';
let fallback: string | null = null;
export function startPath(
    noticeId: string,
    version: number,
    destination: 'conditions' | 'home' = 'conditions',
) {
    const ticket = crypto.randomUUID();
    const params = new URLSearchParams({ ticket, version: String(version) });
    if (destination === 'home') params.set('view', 'home');
    const path =
        '/notices/' + encodeURIComponent(noticeId) + '/start?' + params;
    try {
        sessionStorage.setItem(key, path);
        fallback = null;
    } catch {
        fallback = path;
    }
    return path;
}
export function takeStartIntent(path: string) {
    let pending = fallback;
    try {
        pending = pending ?? sessionStorage.getItem(key);
    } catch {
        /* Use this tab's memory. */
    }
    if (!pending || path !== pending) return false;
    fallback = null;
    try {
        sessionStorage.removeItem(key);
    } catch {
        /* The in-memory intent has been consumed. */
    }
    return true;
}
