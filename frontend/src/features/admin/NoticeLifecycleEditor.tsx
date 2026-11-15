import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { call } from '../../shared/api/transport';
import { Button } from '../../shared/ui/ui';
import type { NoticeLifecycleState, Review } from '../housing/types';
import { NoticeLifecycleNotice } from '../housing/NoticeLifecycleNotice';
import { EvidenceLink } from '../housing/EvidenceLink';

export function NoticeLifecycleEditor({
    review,
    dirty,
}: {
    review: Review;
    dirty: boolean;
}) {
    const current = review.notice.lifecycle;
    const [state, setState] = useState<NoticeLifecycleState>(
        current?.state ?? 'NORMAL',
    );
    const [reason, setReason] = useState('');
    const [label, setLabel] = useState('');
    const [url, setUrl] = useState('');
    const [checkedAt, setCheckedAt] = useState('');
    const [confirmed, setConfirmed] = useState(false);
    const client = useQueryClient();
    const save = useMutation({
        mutationFn: () =>
            call<Review>(`/admin/reviews/${review.id}/lifecycle`, 'PATCH', {
                revision: review.revision,
                state,
                reason,
                evidence: { label, url, checkedAt },
                officialConfirmed: confirmed,
            }),
        onSuccess: () => client.invalidateQueries(),
    });
    return (
        <details>
            <summary>공식 조기 마감·취소 관리</summary>
            <NoticeLifecycleNotice notice={review.notice} />
            {current?.state === 'NORMAL' && current.evidence && (
                <EvidenceLink evidence={current.evidence} />
            )}
            <p>
                운영기관의 공식 안내를 직접 확인한 뒤 변경합니다. 날짜 경과·원문
                접속 실패만으로 취소하지 않습니다. 공개 여부와 개인 준비 기록은
                유지됩니다.
            </p>
            <label>
                공식 상태
                <select
                    value={state}
                    onChange={(event) => {
                        setState(event.target.value as NoticeLifecycleState);
                        setConfirmed(false);
                    }}
                >
                    <option value="NORMAL">정상 · 공식 재개/복원</option>
                    <option value="EARLY_CLOSED">신청 조기 마감</option>
                    <option value="CANCELLED">공고 취소</option>
                </select>
            </label>
            <label>
                확인 내용
                <textarea
                    maxLength={2000}
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                />
            </label>
            <label>
                공식 근거 위치
                <input
                    maxLength={300}
                    value={label}
                    onChange={(event) => setLabel(event.target.value)}
                    placeholder="예: LH 조기 마감 안내"
                />
            </label>
            <label>
                공식 근거 주소
                <input
                    type="url"
                    maxLength={2000}
                    value={url}
                    onChange={(event) => setUrl(event.target.value)}
                    placeholder="https://"
                />
            </label>
            <label>
                근거 확인일
                <input
                    type="date"
                    value={checkedAt}
                    onChange={(event) => setCheckedAt(event.target.value)}
                />
            </label>
            <label>
                <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={(event) => setConfirmed(event.target.checked)}
                />
                공식 근거에서 선택한 상태를 직접 확인했습니다.
            </label>
            <p>
                조기 마감은 신청 시작·마감 알림을, 취소는 모든 기한 알림을
                중지합니다. 상태 변경 소식은 따로 안내합니다.
            </p>
            {dirty && <p>원고 편집을 먼저 저장하거나 닫아 주세요.</p>}
            {save.error && <p role="alert">{save.error.message}</p>}
            <Button
                disabled={
                    dirty ||
                    save.isPending ||
                    !confirmed ||
                    !reason.trim() ||
                    !label.trim() ||
                    !url.startsWith('https://') ||
                    !checkedAt
                }
                onClick={() => save.mutate()}
            >
                {save.isPending ? '저장 중…' : '공식 상태 변경 저장'}
            </Button>
        </details>
    );
}
