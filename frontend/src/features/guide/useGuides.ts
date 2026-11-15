import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../housing/api';

export function useGuides(category: string, keyword: string, page: number) {
    const [search, setSearch] = useState(keyword);
    useEffect(() => {
        const timer = window.setTimeout(() => setSearch(keyword), 250);
        return () => window.clearTimeout(timer);
    }, [keyword]);
    const waiting = search !== keyword;
    const params = new URLSearchParams({
        category,
        keyword: search,
        page: String(page),
    });
    const query = useQuery({
        queryKey: ['guides', category, search, page],
        queryFn: () => api.guides(params.toString()),
        enabled: !waiting,
        gcTime: 60_000,
    });
    return { ...query, waiting };
}
