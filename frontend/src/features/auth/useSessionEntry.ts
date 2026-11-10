import { useEffect } from 'react';
import { useSession } from '../../app/useSession';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { api } from '../housing/api';

type Credentials = { name: string; email: string; password: string };

export function useSessionEntry(destination: string, signup = false) {
    const client = useQueryClient();
    const navigate = useNavigate();
    const { user } = useSession();
    const mutation = useMutation({
        mutationFn: async (entry: Credentials | 'trial') => {
            if (entry === 'trial') return api.startTrial();
            if (signup)
                await api.signup(entry.name, entry.email, entry.password);
            return api.login(entry.email, entry.password);
        },
        onSuccess: async (user) => {
            await client.cancelQueries();
            client.setQueryData(['session'], user);
            client.removeQueries({
                predicate: (q) => q.queryKey[0] !== 'session',
            });
        },
    });
    useEffect(() => {
        if (mutation.isSuccess && user?.id === mutation.data.id)
            navigate(destination, { replace: true });
    }, [mutation.isSuccess, mutation.data, user?.id, destination, navigate]);
    return mutation;
}
