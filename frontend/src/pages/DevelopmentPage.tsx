import { useQuery } from '@tanstack/react-query';
import { getHealth } from '../features/health/api/healthApi';
import styles from './DevelopmentPage.module.css';

export function DevelopmentPage() {
    const health = useQuery({
        queryKey: ['development', 'health'],
        queryFn: ({ signal }) => getHealth(signal),
        retry: false,
    });

    return (
        <main className={styles.container}>
            <p className={styles.label}>집으로 · 개발 환경 확인</p>
            <h1>프로젝트 초기 설정</h1>
            <p>
                이 화면은 제품 화면 구현 전, 로컬 실행과 API 연결을 확인하기
                위한 페이지입니다.
            </p>
            <section className={styles.card} aria-label="연결 상태">
                <h2>프론트엔드 → API → PostgreSQL</h2>
                <div aria-live="polite">
                    {health.isPending && <p>연결을 확인하고 있습니다.</p>}
                    {health.isError && (
                        <p role="alert">
                            연결 확인에 실패했습니다. DB와 백엔드 실행 상태를
                            확인해 주세요.
                        </p>
                    )}
                    {!health.isError && health.data && (
                        <p>
                            API: {health.data.status} / PostgreSQL:{' '}
                            {health.data.database}
                        </p>
                    )}
                </div>
                <button
                    type="button"
                    disabled={health.isFetching}
                    onClick={() => void health.refetch()}
                >
                    {health.isFetching ? '확인 중' : '다시 확인'}
                </button>
            </section>
        </main>
    );
}
