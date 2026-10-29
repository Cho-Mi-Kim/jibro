import { type ReactNode } from 'react';
import { Session } from './useSession';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import { ApiError } from '../shared/api/client';
export function SessionProvider({ children }: { children: ReactNode }) {
    const client = useQueryClient();
    const query = useQuery({
        queryKey: ['session'],
        queryFn: async () => {
            try {
                return await api.me();
            } catch (e) {
                if (e instanceof ApiError && e.status === 401) return null;
                throw e;
            }
        },
        retry: false,
    });
    return (
        <Session.Provider
            value={{
                user: query.data ?? null,
                loading: query.isPending,
                error: query.error,
                refresh: () => client.invalidateQueries(),
            }}
        >
            {children}
        </Session.Provider>
    );
}
