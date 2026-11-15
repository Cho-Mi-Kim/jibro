import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { demoMode } from '../../shared/api/transport';
import { Button } from '../../shared/ui/ui';
import { collectionResponsesApi } from './collectionResponsesApi';
import type { CollectionResponseReceipt } from './collectionResponsesApi';
import s from './CollectionResponses.module.css';

const outcomes: Record<string, string> = {
    REQUESTED: '요청 기록 · 완료 확인 필요',
    ACCEPTED: '응답 검증 통과',
    REJECTED: '응답 검증 실패',
    TRANSPORT_FAILED: '전송 실패',
};
const bodyStates: Record<string, string> = {
    NO_RESPONSE: '받은 응답이 없습니다.',
    INVALID_JSON: 'JSON 응답이 아니어서 본문을 보관하지 않았습니다.',
    TOO_LARGE: '응답 크기 한도를 초과해 본문을 보관하지 않았습니다.',
    UNAVAILABLE: '원본 응답 기록이 없습니다.',
};

function ResponseBody({ receipt }: { receipt: CollectionResponseReceipt }) {
    const [open, setOpen] = useState(false);
    const body = useQuery({
        queryKey: ['admin', 'collection-response', receipt.runId, receipt.id],
        queryFn: () => collectionResponsesApi.get(receipt.runId, receipt.id),
        enabled: open && receipt.sha256 !== null,
    });
    return (
        <li className={s.receipt}>
            <div className={s.heading}>
                <strong>
                    {receipt.page}페이지 · 실행 {receipt.runAttempt}회차
                </strong>
                <span>{outcomes[receipt.outcome] ?? receipt.outcome}</span>
            </div>
            <p>
                조회 기준일 {receipt.queryDate} · HTTP{' '}
                {receipt.httpStatus ?? '응답 없음'}
            </p>
            <p>요청 {new Date(receipt.requestedAt).toLocaleString('ko-KR')}</p>
            {receipt.receivedAt && (
                <p>
                    수신 {new Date(receipt.receivedAt).toLocaleString('ko-KR')}
                </p>
            )}
            {receipt.sha256 ? (
                <>
                    <p className={s.hash}>SHA-256 · {receipt.sha256}</p>
                    {receipt.redacted && <p>인증 정보는 제거했습니다.</p>}
                    <Button
                        className={s.toggle}
                        onClick={() => setOpen(!open)}
                        aria-expanded={open}
                    >
                        {open ? '원본 JSON 닫기' : '원본 JSON 보기'}
                    </Button>
                    {open && body.isPending && (
                        <p role="status">응답을 불러옵니다.</p>
                    )}
                    {open && body.error && (
                        <p role="alert">
                            {body.error.message}{' '}
                            <Button onClick={() => void body.refetch()}>
                                다시 시도
                            </Button>
                        </p>
                    )}
                    {open && body.data && (
                        <pre
                            className={s.json}
                            tabIndex={0}
                            aria-label="인증 정보를 제거한 원본 JSON"
                        >
                            {body.data.bodyJson}
                        </pre>
                    )}
                </>
            ) : (
                <p>
                    {bodyStates[receipt.bodyStatus] ?? '본문 기록이 없습니다.'}
                </p>
            )}
        </li>
    );
}

export function CollectionResponses({ runId }: { runId: string }) {
    const [open, setOpen] = useState(false);
    const responses = useQuery({
        queryKey: ['admin', 'collection-responses', runId],
        queryFn: () => collectionResponsesApi.list(runId),
        enabled: open && !demoMode,
        refetchInterval: open ? 30000 : false,
    });
    if (demoMode) return null;
    return (
        <details
            className={s.panel}
            onToggle={(event) => setOpen(event.currentTarget.open)}
        >
            <summary>수집 원본 응답</summary>
            {open && (
                <>
                    <p>
                        공식 API의 JSON 응답과 요청 이력입니다. 응답 검증과 공고
                        공개 검수는 별개입니다.
                    </p>
                    {responses.isPending && (
                        <p role="status">요청 이력을 불러옵니다.</p>
                    )}
                    {responses.error && (
                        <p role="alert">
                            {responses.error.message}{' '}
                            <Button onClick={() => void responses.refetch()}>
                                다시 시도
                            </Button>
                        </p>
                    )}
                    {responses.data?.length === 0 && (
                        <p>이 실행에는 보관된 원본 응답이 없습니다.</p>
                    )}
                    <ul className={s.list}>
                        {responses.data?.map((receipt) => (
                            <ResponseBody key={receipt.id} receipt={receipt} />
                        ))}
                    </ul>
                </>
            )}
        </details>
    );
}
