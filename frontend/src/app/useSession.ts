import { createContext, useContext } from 'react';
import type { User } from '../features/housing/types';
export const Session = createContext<{
    user: User | null;
    loading: boolean;
    error: Error | null;
    refresh: () => Promise<unknown>;
}>({ user: null, loading: true, error: null, refresh: async () => {} });
export function useSession() {
    return useContext(Session);
}
