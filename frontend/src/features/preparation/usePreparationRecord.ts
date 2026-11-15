import { skipToken, useQuery } from '@tanstack/react-query';
import { useSession } from '../auth/useSession';
import { api } from '../housing/api';

/** One selected record, shared by the home, preparation tabs and fixed actions. */
export function usePreparationRecord(id?: string) {
    const { user } = useSession();
    return useQuery({
        queryKey: ['preparation', user?.id, id],
        queryFn: user && id ? () => api.preparation(id) : skipToken,
        staleTime: 15_000,
    });
}
