import { useId } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '../../shared/ui/ui';
import { safeUrl } from '../../shared/ui/helpers';
import {
    attachmentChecksApi as api,
    type AttachmentCheckResult,
    type AttachmentCheckRun,
} from './attachmentChecksApi';
import s from './AttachmentChecks.module.css';

const runLabels: Record<AttachmentCheckRun['status'], string> = {
    RUNNING: '점검 중',
    SUCCEEDED: '점검 완료',
    PARTIAL: '일부 확인 필요',
    FAILED: '점검 실패',
    SUPERSEDED: '원고 변경으로 결과 미반영',
};
const resultLabels: Record<AttachmentCheckResult['status'], string> = {
    BASELINE: '최초 기준 등록',
    UNCHANGED: '내용 동일',
    CHANGED: '변경 감지',
    FAILED: '확인 실패',
    DEFERRED: '확인 보류',
};

function CheckHistory({ runs }: { runs: AttachmentCheckRun[] }) {
    return (
        <details className={s.history}>
            <summary>최근 점검 이력 · {runs.length}건</summary>
            {runs.length === 0 ? (
                <p>아직 첨부파일을 점검한 기록이 없습니다.</p>
            ) : (
                <ol className={s.runs}>
                    {runs.map((run) => (
                        <li key={run.id} className={s.run}>
                            <div className={s.runHeading}>
                                <strong>{runLabels[run.status]}</strong>
                                <time dateTime={run.startedAt}>
                                    {new Date(run.startedAt).toLocaleString(
                                        'ko-KR',
                                        { timeZone: 'Asia/Seoul' },
                                    )}
                                </time>
                            </div>
                            <p>{run.message}</p>
                            <ul className={s.files}>
                                {run.results.map((result, index) => (
                                    <li key={result.url + ':' + index}>
                                        <div className={s.fileHeading}>
                                            {safeUrl(result.url) ? (
                                                <a
                                                    href={result.url}
                                                    target="_blank"
                                                    rel="noreferrer"
                                                >
                                                    {result.label} ↗
                                                </a>
                                            ) : (
                                                <span>{result.label}</span>
                                            )}
                                            <strong>
                                                {resultLabels[result.status]}
                                            </strong>
                                        </div>
                                        <p>{result.message}</p>
                                    </li>
                                ))}
                            </ul>
                        </li>
                    ))}
                </ol>
            )}
        </details>
    );
}

export function AttachmentChecks({
    noticeId,
    draftDirty,
    active = true,
}: {
    noticeId: string;
    draftDirty: boolean;
    active?: boolean;
}) {
    const headingId = useId();
    const client = useQueryClient();
    const queryKey = ['admin', 'attachment-checks', noticeId];
    const history = useQuery({
        queryKey,
        queryFn: () => api.list(noticeId),
        enabled: active,
        refetchInterval: (query) =>
            active && query.state.data?.some((run) => run.status === 'RUNNING')
                ? 5000
                : false,
    });
    const check = useMutation({
        mutationFn: () => api.run(noticeId),
        retry: false,
        onSuccess: (run) => {
            client.setQueryData<AttachmentCheckRun[]>(queryKey, (previous) =>
                [
                    run,
                    ...(previous ?? []).filter((item) => item.id !== run.id),
                ].slice(0, 20),
            );
        },
        onSettled: () => client.invalidateQueries(),
    });
    const running =
        check.isPending ||
        history.data?.some((run) => run.status === 'RUNNING') === true;

    return (
        <section className={s.panel} aria-labelledby={headingId}>
            <h3 id={headingId}>공식 첨부 변경 확인</h3>
            <p>
                등록된 첨부파일의 현재 내용과 마지막 성공 기록을 비교합니다.
                변경되면 공개를 보류하고 다시 검수합니다.
            </p>
            <p className={s.hint}>
                최초 점검은 비교할 기준을 등록합니다. 그 이전의 변경 여부를
                판정하지 않습니다.
            </p>
            <Button
                type="button"
                className="secondary"
                disabled={draftDirty || running || history.isPending || !active}
                onClick={() => check.mutate()}
            >
                {running ? '첨부 확인 중…' : '첨부 변경 확인'}
            </Button>
            {draftDirty && (
                <p role="status">
                    편집 중인 원고를 먼저 저장하거나 닫은 뒤 점검해 주세요.
                </p>
            )}
            {history.isPending && active && (
                <p role="status">첨부 점검 이력을 불러오고 있습니다.</p>
            )}
            {history.error && (
                <div role="alert">
                    <p>
                        첨부 점검 이력을 불러오지 못했습니다.{' '}
                        {history.error.message}
                    </p>
                    <Button
                        type="button"
                        className="secondary"
                        disabled={history.isFetching}
                        onClick={() => void history.refetch()}
                    >
                        이력 다시 불러오기
                    </Button>
                </div>
            )}
            {check.error && <p role="alert">{check.error.message}</p>}
            {check.data && (
                <p role="status">
                    {runLabels[check.data.status]} · {check.data.message}
                </p>
            )}
            {history.data && <CheckHistory runs={history.data} />}
        </section>
    );
}
