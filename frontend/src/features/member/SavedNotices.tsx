import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router';
import { useSession } from '../auth/useSession';
import { Icon } from '../../shared/ui/Icon';
import { memberApi } from './memberApi';
import { useSavedNotices } from './useSavedNotices';
import styles from './Controls.module.css';

export function Bookmark({ id }: { id: string }) {
    const { user } = useSession(),
        saved = useSavedNotices(),
        client = useQueryClient(),
        location = useLocation(),
        navigate = useNavigate();
    const selected = saved.data?.some((n) => n.id === id) ?? false;
    const mutation = useMutation({
        mutationFn: () => memberApi.save(id, !selected),
        onSuccess: async () => {
            await client.invalidateQueries({
                queryKey: ['saved-notices', user?.id],
            });
            await client.invalidateQueries({ queryKey: ['alerts', user?.id] });
        },
    });
    return (
        <div>
            <button
                className={styles.bookmark}
                aria-label={selected ? '관심 공고 해제' : '관심 공고 저장'}
                aria-pressed={selected}
                disabled={mutation.isPending || (!!user && saved.isPending)}
                onClick={() => {
                    if (!user) {
                        navigate(
                            '/login?returnTo=' +
                                encodeURIComponent(
                                    location.pathname + location.search,
                                ),
                        );
                        return;
                    }
                    if (saved.isError) {
                        void saved.refetch();
                        return;
                    }
                    mutation.mutate();
                }}
            >
                <Icon name="bookmark" />
                <span>{selected ? '저장됨' : '관심'}</span>
            </button>
            {(saved.error || mutation.error) && user && (
                <small role="alert">
                    {(saved.error ?? mutation.error)?.message}
                </small>
            )}
        </div>
    );
}
