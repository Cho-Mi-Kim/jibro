export function noticeHomePath(id: string) {
    return '/?' + new URLSearchParams({ notice: id });
}

// The home preference contains only a public notice ID and belongs to this tab and viewer.
const fallback = new Map<string, string | null>();
const key = (userId?: string) => 'jibro.home-notice.' + (userId ?? 'visitor');

export function rememberedNotice(userId?: string) {
    const name = key(userId);
    if (fallback.has(name)) return fallback.get(name) ?? null;
    try {
        return sessionStorage.getItem(name);
    } catch {
        return null;
    }
}

export function rememberNotice(id: string | null, userId?: string) {
    const name = key(userId);
    try {
        if (id) sessionStorage.setItem(name, id);
        else sessionStorage.removeItem(name);
        fallback.delete(name);
    } catch {
        fallback.set(name, id);
    }
}
