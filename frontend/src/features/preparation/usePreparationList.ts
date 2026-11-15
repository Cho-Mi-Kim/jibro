import { useQuery } from '@tanstack/react-query';
import { useSession } from '../auth/useSession';
import { api } from '../housing/api';

/** Small picker/count metadata shared by every preparation entry point. */
export function usePreparationList(enabled = true) {
    const { user } = useSession();
    return useQuery({
        queryKey: ['preparations', user?.id],
        queryFn: api.preparations,
        enabled: !!user && enabled,
        staleTime: 15_000,
    });
}
