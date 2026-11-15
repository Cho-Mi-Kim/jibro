import { skipToken, useQuery } from '@tanstack/react-query';
import { api } from './api';
import { noticeRefreshInterval } from './noticeRefresh';
export function useNotice(id?: string) {
    return useQuery({
        queryKey: ['notice', id],
        queryFn: id ? () => api.notice(id) : skipToken,
        staleTime: 15_000,
        refetchInterval: (query) =>
            noticeRefreshInterval(query.state.data ? [query.state.data] : []),
    });
}
