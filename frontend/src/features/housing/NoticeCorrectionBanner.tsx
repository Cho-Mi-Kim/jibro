import { useQuery } from '@tanstack/react-query';
import { Link, useLocation } from 'react-router';
import { noticeCorrection } from './noticeCorrectionApi';
import { EvidenceLink } from './EvidenceLink';
import type { Notice } from './types';
import styles from './NoticeCorrectionBanner.module.css';

/** Keep the original notice and personal records intact; let the reader choose the verified correction. */
export function NoticeCorrectionBanner({ notice }: { notice: Notice }) {
    const { state } = useLocation();
    const query = useQuery({
        queryKey: ['notice-correction', notice.id],
        queryFn: () => noticeCorrection(notice.id),
        staleTime: 0,
    });
    if (query.isError)
        return (
            <section className={styles.banner} aria-label="정정 공고 확인">
                <p>정정 공고 연결을 확인하지 못했어요.</p>
                <button type="button" onClick={() => void query.refetch()}>
                    다시 확인
                </button>
            </section>
        );
    const correction = query.data;
    if (!correction) return null;
    return (
        <section className={styles.banner} aria-label="정정 공고 안내">
            <h2>정정된 공고가 있어요</h2>
            <p>{correction.reason}</p>
            <Link
                className={styles.link}
                to={'/notices/' + correction.noticeId}
                state={state}
            >
                정정 공고로 이동
            </Link>
            <details>
                <summary>정정 근거 확인</summary>
                <p>{correction.title}</p>
                <EvidenceLink evidence={correction.evidence} />
            </details>
        </section>
    );
}
