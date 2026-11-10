import { useEffect } from 'react';
import { choosePreparation } from './useActivePreparation';
import { useParams } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../housing/api';
import type { Preparation } from '../housing/types';
import { useSession } from '../../app/useSession';
export function usePreparation() {
    const { preparationId = '' } = useParams();
    const { user } = useSession();
    const query = useQuery({
        queryKey: ['preparation', user?.id, preparationId],
        queryFn: () => api.preparation(preparationId),
        enabled: !!user,
    });
    const preparation = query.isSuccess ? query.data : undefined;
    useEffect(() => {
        if (user && preparation && preparation.progress < 100)
            choosePreparation(user.id, preparation.id);
    }, [user, preparation]);
    return { ...query, data: preparation };
}
export function useSaved() {
    const client = useQueryClient();
    const { user } = useSession();
    return (p: Preparation) => {
        client.setQueryData(['preparation', user?.id, p.id], p);
        void client.invalidateQueries({ queryKey: ['preparations'] });
    };
}
