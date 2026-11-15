import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { call } from '../../shared/api/transport';
import { Button } from '../../shared/ui/ui';
import { safeUrl } from '../../shared/ui/helpers';
import { GuidanceReviewPreview } from './GuidanceReviewPreview';
import styles from './NoticeExtraction.module.css';

interface Excerpt {
    category: string;
    text: string;
    location: string;
    url: string;
}
interface Source {
    label: string;
    url: string;
    status: string;
    message: string;
    sha256: string | null;
    excerpts: Excerpt[];
}
interface Draft {
    id: string;
    revision: number;
    status: string;
    checkedAt: string | null;
    message: string;
    sources: Source[];
}
const categories: Record<string, string> = {
    CONDITION: '조건',
    DOCUMENT: '서류',
    SCHEDULE: '일정',
};
const labels: Record<string, string> = {
    QUEUED: '발췌 대기',
    RUNNING: '발췌 중',
    READY: '검토 준비',
    PARTIAL: '일부 확인 필요',
    FAILED: '직접 확인 필요',
    SUPERSEDED: '이전 원고 기준',
};

export function NoticeExtraction({
    noticeId,
    revision,
    active,
    dirty,
}: {
    noticeId: string;
    revision: number;
    active: boolean;
    dirty: boolean;
}) {
    const client = useQueryClient();
    const [copied, setCopied] = useState('');
    const path = `/admin/reviews/${noticeId}/extractions`;
    const query = useQuery({
        queryKey: ['admin', 'extractions', noticeId],
        queryFn: () => call<Draft[]>(path),
        enabled: active,
        refetchInterval: (q) =>
            active &&
            q.state.data?.some((d) => ['QUEUED', 'RUNNING'].includes(d.status))
                ? 3000
                : false,
    });
    const run = useMutation({
        mutationFn: () => call<Draft>(path, 'POST'),
        onSuccess: () =>
            client.invalidateQueries({
                queryKey: ['admin', 'extractions', noticeId],
            }),
    });
    const adopt = useMutation({
        mutationFn: (id: string) =>
            call(`${path}/${id}/attachments`, 'POST', { revision }),
        onSuccess: () => client.invalidateQueries(),
    });
    const file = useMutation({
        mutationFn: ({ id, url }: { id: string; url: string }) =>
            call<Source>(`${path}/${id}/file`, 'POST', { url }),
        onSuccess: () =>
            client.invalidateQueries({
                queryKey: ['admin', 'extractions', noticeId],
            }),
    });
    const latest = query.data?.[0];
    const busy =
        run.isPending ||
        adopt.isPending ||
        file.isPending ||
        (!!latest && ['QUEUED', 'RUNNING'].includes(latest.status));
    return (
        <section className={styles.panel} aria-label="공식 원문 발췌 초안">
            <h3>공식 원문 발췌 초안</h3>
            <p>
                조건·서류·일정 문구와 출처 위치를 모읍니다. 자동 발췌는 공개
                원고나 자격 판정이 아닙니다. 원문을 대조한 뒤 구조화 원고에
                반영하세요.
            </p>
            <Button
                className="secondary"
                disabled={!active || dirty || busy}
                onClick={() => run.mutate()}
            >
                본문·첨부 발췌
            </Button>
            {dirty && <p>편집 중인 원고를 먼저 저장하거나 닫아 주세요.</p>}
            {(query.error || run.error || adopt.error || file.error) && (
                <p role="alert">
                    {query.error?.message ||
                        run.error?.message ||
                        adopt.error?.message ||
                        file.error?.message}
                </p>
            )}
            {query.error && (
                <Button
                    className="secondary"
                    disabled={!active || query.isFetching}
                    onClick={() => void query.refetch()}
                >
                    발췌 이력 다시 불러오기
                </Button>
            )}
            {latest && (
                <>
                    <p role="status">
                        <strong>{labels[latest.status]}</strong> ·{' '}
                        {latest.message}
                    </p>
                    {latest.checkedAt && (
                        <p>
                            자동 발췌 시각 ·{' '}
                            {new Date(latest.checkedAt).toLocaleString(
                                'ko-KR',
                                { timeZone: 'Asia/Seoul' },
                            )}
                        </p>
                    )}
                    <GuidanceReviewPreview
                        noticeId={noticeId}
                        extractionId={latest.id}
                        sourceVersion={`${latest.revision}:${latest.checkedAt}:${latest.sources.map((source) => `${source.status}:${source.sha256}`).join('|')}`}
                        enabled={
                            active &&
                            !dirty &&
                            !busy &&
                            latest.revision === revision &&
                            ['READY', 'PARTIAL', 'FAILED'].includes(
                                latest.status,
                            )
                        }
                    />
                    {latest.sources.map((source, i) => (
                        <details key={source.url + i} className={styles.source}>
                            <summary>
                                {source.label} · {source.excerpts.length}개 발췌
                            </summary>
                            <p>{source.message}</p>
                            {safeUrl(source.url) && (
                                <a
                                    href={source.url}
                                    target="_blank"
                                    rel="noreferrer"
                                >
                                    원문 직접 확인 ↗
                                </a>
                            )}
                            {source.label !== '공식 상세 본문' && (
                                <Button
                                    className="secondary"
                                    disabled={
                                        !active ||
                                        busy ||
                                        dirty ||
                                        latest.status === 'SUPERSEDED' ||
                                        latest.revision !== revision
                                    }
                                    onClick={() =>
                                        file.mutate({
                                            id: latest.id,
                                            url: source.url,
                                        })
                                    }
                                >
                                    이 첨부 다시 발췌
                                </Button>
                            )}
                            <ul className={styles.excerpts}>
                                {source.excerpts.map((excerpt, j) => (
                                    <li key={j}>
                                        <strong>
                                            {categories[excerpt.category]} ·{' '}
                                            {excerpt.location}
                                        </strong>
                                        <blockquote>{excerpt.text}</blockquote>
                                        <Button
                                            className="secondary"
                                            onClick={() => {
                                                void navigator.clipboard
                                                    .writeText(
                                                        `${excerpt.text}\n출처: ${excerpt.location}\n${excerpt.url}`,
                                                    )
                                                    .then(
                                                        () =>
                                                            setCopied(
                                                                `${i}:${j}`,
                                                            ),
                                                        () =>
                                                            setCopied('error'),
                                                    );
                                            }}
                                        >
                                            발췌 복사
                                        </Button>
                                        {copied === `${i}:${j}` && (
                                            <span role="status">
                                                복사했습니다.
                                            </span>
                                        )}
                                    </li>
                                ))}
                            </ul>
                        </details>
                    ))}
                    {copied === 'error' && (
                        <p role="alert">
                            복사하지 못했습니다. 발췌 문구를 직접 선택해 주세요.
                        </p>
                    )}
                    {['READY', 'PARTIAL'].includes(latest.status) && (
                        <Button
                            className="secondary"
                            disabled={
                                !active ||
                                busy ||
                                dirty ||
                                latest.revision !== revision
                            }
                            onClick={() => {
                                if (
                                    window.confirm(
                                        '확인한 첨부 목록을 원고에 반영하고 공개를 보류할까요? 질문·서류·일정은 자동 생성하지 않습니다.',
                                    )
                                )
                                    adopt.mutate(latest.id);
                            }}
                        >
                            확인한 첨부 목록을 원고에 반영
                        </Button>
                    )}
                    <small>
                        이 제품은 한글과컴퓨터의 한글 문서 파일(.hwp) 공개
                        문서를 참고하여 개발하였습니다.
                    </small>
                </>
            )}
        </section>
    );
}
