import { useMutation } from '@tanstack/react-query';
import { Button } from '../../shared/ui/ui';
import { safeUrl } from '../../shared/ui/helpers';
import {
    evidenceChecksApi,
    type EvidenceCheckResult,
} from './evidenceChecksApi';
import s from './EvidenceChecks.module.css';

const statusLabel: Record<EvidenceCheckResult['status'], string> = {
    REACHABLE: '응답 확인 · 원문 대조 필요',
    UNAVAILABLE: '주소를 찾을 수 없음 · 원문 확인 필요',
    MANUAL: '브라우저에서 직접 확인 필요',
    MISSING: '주소 미등록 · 발급 기관과 원문 확인 필요',
    UNSUPPORTED: '자동 점검 미지원 · 직접 확인 필요',
    UNCONFIRMED: '연결 확인 불가 · 다시 확인 필요',
    DEFERRED: '이번 점검에서 보류 · 직접 확인 필요',
};

export function EvidenceChecks({
    noticeId,
    revision,
    dirty,
}: {
    noticeId: string;
    revision: number;
    dirty: boolean;
}) {
    const check = useMutation({
        mutationFn: () => evidenceChecksApi.run(noticeId, revision),
    });
    const report =
        !check.isPending &&
        !check.isError &&
        !dirty &&
        check.data?.revision === revision
            ? check.data
            : undefined;

    return (
        <section className={s.panel} aria-label="출처·발급 링크 점검">
            <h3>출처·발급 링크 점검</h3>
            <p className={s.note}>
                저장한 원고의 주소 응답을 점검합니다. 공고 내용, 발췌 위치와
                발급 대상은 공식 원문에서 직접 대조해 주세요. 검수 결과와
                확인일은 자동으로 바뀌지 않습니다.
            </p>
            <Button
                disabled={dirty || check.isPending}
                onClick={() => check.mutate()}
            >
                {check.isPending ? '주소 점검 중…' : '출처·발급 링크 점검'}
            </Button>
            {dirty && <p className={s.note}>원고를 저장한 뒤 점검해 주세요.</p>}
            {check.error && <p role="alert">{check.error.message}</p>}
            {report && (
                <>
                    <p className={s.note}>
                        검수 {report.revision} · 주소 점검{' '}
                        {new Date(report.checkedAt).toLocaleString('ko-KR', {
                            timeZone: 'Asia/Seoul',
                        })}
                    </p>
                    <ul className={s.list}>
                        {report.results.map((result, index) => (
                            <li
                                className={s.item}
                                key={`${result.kind}-${result.url}-${index}`}
                            >
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
                                <span>{statusLabel[result.status]}</span>
                                {result.checkedAt && (
                                    <span className={s.note}>
                                        원고에 기록된 출처 확인일 ·{' '}
                                        {result.checkedAt}
                                    </span>
                                )}
                            </li>
                        ))}
                    </ul>
                </>
            )}
        </section>
    );
}
