import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate } from 'react-router';
import { useSession } from '../../app/useSession';
import { Icon } from '../../shared/ui/Icon';
import { Heading, State } from '../../shared/ui/ui';
import { memberApi } from './memberApi';
import styles from './Controls.module.css';
function useSavedNotices() {
    const { user } = useSession();
    return useQuery({
        queryKey: ['saved-notices', user?.id],
        queryFn: memberApi.saved,
        enabled: !!user,
    });
}
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
export function SavedPage() {
    const saved = useSavedNotices();
    return (
        <>
            <Heading title="관심 공고" backTo="/my" />
            <State
                loading={saved.isPending}
                error={saved.error}
                retry={() => void saved.refetch()}
            />
            {saved.isSuccess && (
                <section className={styles.savedPage} aria-label="저장한 공고">
                    {!saved.data.length && (
                        <p className={styles.savedEmpty}>
                            저장한 공고가 없습니다.
                        </p>
                    )}
                    <ul className={styles.savedList}>
                        {saved.data.map((n) => (
                            <li key={n.id} className={styles.savedCard}>
                                <Link
                                    className={styles.savedNoticeLink}
                                    to={'/notices/' + n.id}
                                >
                                    <h2>{n.title}</h2>
                                    <p>
                                        {n.agency} · {n.region}
                                    </p>
                                </Link>
                                <Bookmark id={n.id} />
                            </li>
                        ))}
                    </ul>
                    <Link
                        className={`${styles.link} ${styles.savedBrowse}`}
                        to="/notices"
                    >
                        <Icon name="search" />
                        공고 찾기
                    </Link>
                </section>
            )}
        </>
    );
}
