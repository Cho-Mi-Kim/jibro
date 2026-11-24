import { skipToken, useQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useSession } from '../auth/useSession';
import { api } from '../housing/api';

/** One selected record, shared by the home, preparation tabs and fixed actions. */
export function usePreparationRecord(id?: string, expectedRevision?: number) {
    const { user } = useSession();
    const query = useQuery({
        queryKey: ['preparation', user?.id, id],
        queryFn: user && id ? () => api.preparation(id) : skipToken,
        staleTime: 15_000,
    });
    const attempted = useRef<{
        userId: string;
        id: string;
        revision: number;
    } | null>(null);
    const { data, isFetching, refetch } = query;
    const userId = user?.id;
    useEffect(() => {
        if (
            !userId ||
            !id ||
            expectedRevision === undefined ||
            !data ||
            data.revision >= expectedRevision ||
            isFetching ||
            (attempted.current?.userId === userId &&
                attempted.current.id === id &&
                attempted.current.revision === expectedRevision)
        )
            return;
        // A newer list revision may arrive while the shared detail cache is still fresh.
        attempted.current = { userId, id, revision: expectedRevision };
        void refetch({ cancelRefetch: false });
    }, [userId, id, expectedRevision, data, isFetching, refetch]);
    return query;
}
