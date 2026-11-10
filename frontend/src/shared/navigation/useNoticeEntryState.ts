import { useLocation } from 'react-router';

export function useNoticeEntryState() {
    const location = useLocation();
    return location.pathname === '/'
        ? { homeReturn: '/' + location.search + location.hash }
        : undefined;
}
export function getHomeReturn(state: { homeReturn?: unknown } | null) {
    return typeof state?.homeReturn === 'string' &&
        /^\/(?:[?#]|$)/.test(state.homeReturn)
        ? state.homeReturn
        : undefined;
}
