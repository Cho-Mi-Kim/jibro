import { useQuery } from '@tanstack/react-query';
import { Button } from '../../shared/ui/ui';
import { safeUrl } from '../../shared/ui/helpers';
import type { Evidence } from '../housing/types';
import { loadGuidancePreview } from './guidanceReviewApi';
import styles from './GuidanceReviewPreview.module.css';

function EvidenceLink({ evidence }: { evidence: Evidence | null }) {
    if (!evidence) return null;
    return (
        <p className={styles.evidence}>
            {safeUrl(evidence.url) ? (
                <a href={evidence.url} target="_blank" rel="noreferrer">
                    {evidence.label} ↗
                </a>
            ) : (
                <span>{evidence.label}</span>
            )}
            {evidence.checkedAt && <span>확인 {evidence.checkedAt}</span>}
        </p>
    );
}

const issueLabels: Record<string, string> = {
    DOCUMENT_VARIANT_CONTEXT_UNVERIFIED:
        '같은 공고의 문서 형식별 내용이 일치하는지 확인해야 합니다.',
    AUTHORITATIVE_SOURCE_INCOMPLETE: '공식 문서의 내용을 모두 읽지 못했습니다.',
    SOURCE_COVERAGE_INCOMPLETE: '아직 확인하지 못한 공식 첨부가 있습니다.',
    SOURCE_NOT_FINISHED: '원문 발췌가 끝나지 않았습니다.',
    EVIDENCE_MULTIPLE_GOVERNING_SOURCES_REQUIRE_REVIEW:
        '공고와 추가 문서 사이의 조건을 함께 대조해야 합니다.',
    EVIDENCE_GUIDANCE_TOO_MANY_QUESTIONS:
        '중복 질문과 조건 분기를 추가로 정리해야 합니다.',
    EVIDENCE_DOCUMENT_SECTION_MISSING: '제출서류 구간을 확인해야 합니다.',
    EVIDENCE_QUALIFICATION_SECTION_MISSING: '신청 자격 구간을 확인해야 합니다.',
    EVIDENCE_INSTITUTIONAL_SCOPE_UNSUPPORTED:
        '개인 주거 신청과 다른 대상의 공고입니다.',
};

export function GuidanceReviewPreview({
    noticeId,
    extractionId,
    sourceVersion,
    enabled,
}: {
    noticeId: string;
    extractionId: string;
    sourceVersion: string;
    enabled: boolean;
}) {
    const query = useQuery({
        queryKey: [
            'admin',
            'guidance-preview',
            noticeId,
            extractionId,
            sourceVersion,
        ],
        queryFn: () => loadGuidancePreview(noticeId, extractionId),
        enabled: false,
        retry: false,
        gcTime: 0,
    });
    const preview = enabled ? query.data : undefined;
    const candidate = preview?.candidate;
    return (
        <section className={styles.panel} aria-label="준비 안내 초안 검토">
            <Button
                className="secondary"
                disabled={!enabled || query.isFetching}
                onClick={() => {
                    if (enabled) void query.refetch();
                }}
            >
                {query.isFetching ? '초안 확인 중…' : '준비 안내 초안 검토'}
            </Button>
            {enabled && query.error && (
                <p role="alert">{query.error.message}</p>
            )}
            {preview && (
                <div className={styles.result}>
                    <p role="status" className={styles.status}>
                        <strong>
                            {preview.ready
                                ? '자동 검수 조건 충족'
                                : '공개 보류'}
                        </strong>
                        <span>
                            검토용 초안입니다. 이 화면에서 공개 원고나 개인 준비
                            기록을 변경하지 않습니다.
                        </span>
                    </p>
                    {preview.findings.length > 0 && (
                        <section aria-label="원문 확인 사항">
                            <h4>원문 확인 사항</h4>
                            <ul className={styles.findings}>
                                {preview.findings.map((finding, index) => (
                                    <li key={`${finding.code}:${index}`}>
                                        <p>{finding.message}</p>
                                        <EvidenceLink
                                            evidence={finding.evidence}
                                        />
                                    </li>
                                ))}
                            </ul>
                        </section>
                    )}
                    {preview.issues.length > 0 && (
                        <details className={styles.details} open>
                            <summary>
                                공개 전 확인할 항목 {preview.issues.length}개
                            </summary>
                            <ul className={styles.findings}>
                                {preview.issues.map((issue, index) => (
                                    <li key={`${issue}:${index}`}>
                                        <p>
                                            {issueLabels[issue.split(':')[0]] ??
                                                '원문과 대조가 필요한 항목입니다.'}
                                        </p>
                                        <code>{issue}</code>
                                    </li>
                                ))}
                            </ul>
                        </details>
                    )}
                    {candidate ? (
                        <>
                            <p>{candidate.summary}</p>
                            <div
                                className={styles.counts}
                                aria-label="생성된 초안 항목 수"
                            >
                                <span>
                                    질문{' '}
                                    <strong>
                                        {candidate.questions.length}개
                                    </strong>
                                </span>
                                <span>
                                    서류{' '}
                                    <strong>
                                        {candidate.documents.length}개
                                    </strong>
                                </span>
                                <span>
                                    일정{' '}
                                    <strong>
                                        {candidate.schedules.length}개
                                    </strong>
                                </span>
                            </div>
                            <details className={styles.details}>
                                <summary>생성된 질문 보기</summary>
                                <ol className={styles.items}>
                                    {candidate.questions.map((question) => (
                                        <li key={question.id}>
                                            <h4>{question.title}</h4>
                                            <p className={styles.reason}>
                                                {question.reason}
                                            </p>
                                            {question.options && (
                                                <ul>
                                                    {question.options.map(
                                                        (option) => (
                                                            <li
                                                                key={
                                                                    option.value
                                                                }
                                                            >
                                                                {option.label}
                                                                {option.requiresFollowUp &&
                                                                    ' · 추가 확인'}
                                                            </li>
                                                        ),
                                                    )}
                                                </ul>
                                            )}
                                            <EvidenceLink
                                                evidence={question.evidence}
                                            />
                                        </li>
                                    ))}
                                </ol>
                            </details>
                            <details className={styles.details}>
                                <summary>생성된 서류 보기</summary>
                                <ul className={styles.items}>
                                    {candidate.documents.map((document) => (
                                        <li key={document.id}>
                                            <h4>{document.title}</h4>
                                            <p>
                                                {document.conditionQuestionId
                                                    ? '조건에 따라 확인'
                                                    : '공통 준비 항목'}
                                                {document.issuer &&
                                                    ` · ${document.issuer}`}
                                            </p>
                                            {document.method && (
                                                <p>{document.method}</p>
                                            )}
                                            {document.conditionQuestionId && (
                                                <p>
                                                    연결 질문 ·{' '}
                                                    {candidate.questions.find(
                                                        (question) =>
                                                            question.id ===
                                                            document.conditionQuestionId,
                                                    )?.title ??
                                                        '연결 확인 필요'}
                                                </p>
                                            )}
                                            {!!document.conditionAnswerValues
                                                ?.length && (
                                                <p>
                                                    제출 대상 답변 ·{' '}
                                                    {document.conditionAnswerValues
                                                        .map(
                                                            (value) =>
                                                                candidate.questions
                                                                    .find(
                                                                        (
                                                                            question,
                                                                        ) =>
                                                                            question.id ===
                                                                            document.conditionQuestionId,
                                                                    )
                                                                    ?.options?.find(
                                                                        (
                                                                            option,
                                                                        ) =>
                                                                            option.value ===
                                                                            value,
                                                                    )?.label ??
                                                                value,
                                                        )
                                                        .join(' / ')}
                                                </p>
                                            )}
                                            <EvidenceLink
                                                evidence={document.evidence}
                                            />
                                        </li>
                                    ))}
                                </ul>
                            </details>
                            <details className={styles.details}>
                                <summary>생성된 일정 보기</summary>
                                <p>
                                    접수기간 ·{' '}
                                    {preview.startDate ?? '확인 필요'} ~{' '}
                                    {preview.endDate ?? '확인 필요'}
                                </p>
                                <ul className={styles.items}>
                                    {candidate.schedules.map((schedule) => (
                                        <li key={schedule.id}>
                                            <h4>{schedule.title}</h4>
                                            <p>{schedule.date}</p>
                                            <EvidenceLink
                                                evidence={schedule.evidence}
                                            />
                                        </li>
                                    ))}
                                </ul>
                            </details>
                        </>
                    ) : (
                        <p>
                            아직 질문·서류·일정 초안을 만들지 못했습니다. 위
                            확인 항목을 먼저 검토해 주세요.
                        </p>
                    )}
                </div>
            )}
        </section>
    );
}
