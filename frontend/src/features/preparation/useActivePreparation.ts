import { rememberNotice } from '../../shared/navigation/homeNotice';
import { useSyncExternalStore } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../../app/useSession';
import { api } from '../housing/api';

const changeEvent = 'jibro.active-preparation';
const memory = new Map<string, string>();
function selected(userId?: string) {
    if (!userId) return null;
    try {
        return (
            memory.get(userId) ?? localStorage.getItem('jibro.active.' + userId)
        );
    } catch {
        return memory.get(userId) ?? null;
    }
}
export function choosePreparation(userId: string, id: string) {
    rememberNotice(null, userId);
    if (selected(userId) === id) return;
    try {
        localStorage.setItem('jibro.active.' + userId, id);
        memory.delete(userId);
    } catch {
        memory.set(userId, id);
    }
    window.dispatchEvent(new Event(changeEvent));
}
function subscribe(callback: () => void) {
    window.addEventListener(changeEvent, callback);
    window.addEventListener('storage', callback);
    return () => {
        window.removeEventListener(changeEvent, callback);
        window.removeEventListener('storage', callback);
    };
}
export function useActivePreparation() {
    const { user } = useSession();
    const id = useSyncExternalStore(subscribe, () => selected(user?.id));
    const query = useQuery({
        queryKey: ['preparations', user?.id],
        queryFn: api.preparations,
        enabled: !!user,
    });
    const items = query.isSuccess ? query.data : [];
    const active =
        items.find((item) => item.id === id) ??
        items.find((item) => item.progress < 100) ??
        items[0];
    return {
        query,
        active,
        select: (id: string) => {
            if (user && items.some((item) => item.id === id))
                choosePreparation(user.id, id);
        },
    };
}
