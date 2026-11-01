import { useQuery } from '@tanstack/react-query';
import { api } from '../../features/housing/api';
export function useNotice(id?: string) {
    return useQuery({
        queryKey: ['notice', id],
        queryFn: () => api.notice(id!),
        enabled: !!id,
    });
}
export function displayDate(value?: string | null) {
    if (!value) return '확인 필요';
    return value.replace(/-/g, '.').replace('T', ' ').slice(0, 16);
}
export function daysLeft(value?: string | null, now = new Date()) {
    if (!value) return '일정 확인';
    const day = value.slice(0, 10);
    const today = new Intl.DateTimeFormat('en-CA', {
        timeZone: 'Asia/Seoul',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
    }).format(now);
    const left = Math.round(
        (Date.parse(day + 'T00:00:00+09:00') -
            Date.parse(today + 'T00:00:00+09:00')) /
            86400000,
    );
    if (!Number.isFinite(left)) return '일정 확인';
    if (left < 0) return '일정 경과';
    return left === 0 ? 'D-Day' : 'D-' + left;
}
