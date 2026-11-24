import { useQuery } from '@tanstack/react-query';
import { useSession } from '../auth/useSession';
import { noticeListRefreshInterval } from '../housing/noticeRefresh';
import { memberApi } from './memberApi';

export function useSavedNotices(showStatuses = false) {
    const { user } = useSession();
    return useQuery({
        queryKey: ['saved-notices', user?.id],
        queryFn: memberApi.saved,
        enabled: !!user,
        refetchInterval: showStatuses
            ? (query) => noticeListRefreshInterval(query.state.data ?? [])
            : false,
    });
}
