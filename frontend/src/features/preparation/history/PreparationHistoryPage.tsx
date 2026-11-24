import { cx } from '../../../shared/styles/classes';
import { House } from '../PreparationProgress';
import { useQuery } from '@tanstack/react-query';
import { useParams, useSearchParams } from 'react-router';
import { useSession } from '../../auth/useSession';
import { noticeListTitle } from '../../housing/noticeDisplay';
import { Heading, State } from '../../../shared/ui/ui';
import { HistorySnapshot } from './HistorySnapshot';
import { preparationHistoryApi } from './historyApi';
import styles from './PreparationHistory.module.css';

const savedDate = (value: string) => {
    const date = new Date(value);
    return Number.isNaN(date.valueOf())
        ? value
        : date.toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });
};

export function PreparationHistoryPage() {
    const { preparationId = '' } = useParams();
    const [search, setSearch] = useSearchParams();
    const { user } = useSession();
    const history = useQuery({
        queryKey: ['preparation-history', user?.id, preparationId],
        queryFn: () => preparationHistoryApi.list(preparationId),
        enabled: Boolean(user && preparationId),
    });
    const requested = search.get('version');
    const selected =
        requested === null
            ? history.data?.entries[0]?.version
            : Number(requested);
    const entry = history.data?.entries.find(
        (item) => item.version === selected,
    );
    const detail = useQuery({
        queryKey: [
            'preparation-history-detail',
            user?.id,
            preparationId,
            entry?.version,
        ],
        queryFn: () => preparationHistoryApi.get(preparationId, entry!.version),
        enabled: Boolean(user && entry),
    });
    const title = detail.data?.preparation.title || history.data?.title || '';
    const backTo = history.data?.archived
        ? '/archive'
        : `/preparations/${preparationId}`;

    return (
        <div className={styles.page}>
            <Heading title="이전 안내와 답변" backTo={backTo} />
            <State
                loading={history.isPending}
                error={history.error}
                retry={() => void history.refetch()}
            />
            {history.data && (
                <>
                    <section className={styles.card}>
                        <h2 aria-label={title} title={title}>
                            {noticeListTitle(title)}
                        </h2>
                        <p className={styles.description}>
                            새 안내로 갱신하기 전에 저장한 개인 준비 기록입니다.
                            현재 조건을 다시 판단하거나 준비 상태를 변경하지
                            않습니다.
                        </p>
                        {history.data.archived && (
                            <p className={styles.description}>
                                보관한 여정의 이전 기록을 보고 있습니다.
                            </p>
                        )}
                    </section>
                    {history.data.entries.length === 0 ? (
                        <section className={styles.card} role="status">
                            <h2>저장된 이전 안내가 없습니다</h2>
                            <p>
                                준비 중인 공고에 새 안내를 적용하면 이전 질문,
                                답변과 서류 기록이 여기에 남습니다.
                            </p>
                        </section>
                    ) : (
                        <>
                            <nav
                                aria-label="이전 준비 기록 선택"
                                className={styles.versions}
                            >
                                {history.data.entries.map((item) => (
                                    <button
                                        type="button"
                                        key={item.version}
                                        aria-current={
                                            item.version === selected
                                                ? 'true'
                                                : undefined
                                        }
                                        onClick={() =>
                                            setSearch({
                                                version: String(item.version),
                                            })
                                        }
                                    >
                                        <span>
                                            저장 {savedDate(item.savedAt)}
                                        </span>
                                    </button>
                                ))}
                            </nav>
                            {!entry ? (
                                <p className={styles.card} role="alert">
                                    선택한 준비 기록이 없습니다. 목록에서 다른
                                    기록을 선택해 주세요.
                                </p>
                            ) : (
                                <>
                                    <State
                                        loading={detail.isPending}
                                        error={detail.error}
                                        retry={() => void detail.refetch()}
                                    />
                                    {detail.data && (
                                        <article
                                            aria-label={`${savedDate(detail.data.savedAt)} 저장 기록`}
                                        >
                                            <header
                                                className={`${styles.recordHeading} ${cx('explanation-card')}`}
                                            >
                                                <h2>이전 준비 기록</h2>
                                                <p>
                                                    저장{' '}
                                                    {savedDate(
                                                        detail.data.savedAt,
                                                    )}{' '}
                                                    (한국 시각)
                                                </p>
                                                <House
                                                    historical
                                                    progress={
                                                        detail.data.preparation
                                                            .progress
                                                    }
                                                    stage={
                                                        detail.data.preparation
                                                            .houseStage
                                                    }
                                                />
                                            </header>
                                            <HistorySnapshot
                                                key={detail.data.version}
                                                preparation={
                                                    detail.data.preparation
                                                }
                                            />
                                        </article>
                                    )}
                                </>
                            )}
                        </>
                    )}
                </>
            )}
        </div>
    );
}
