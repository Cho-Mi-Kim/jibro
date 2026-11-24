import type { Edition } from '../features/member/memberApi';
import { applicationStatuses } from '../features/housing/noticeLabels';
import { recruitmentRegionLabel } from '../features/housing/noticeRegions';
import { useNoticeDetailReturn } from '../features/housing/NoticeListVisit';
import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { memberApi } from '../features/member/memberApi';
import { Heading, State } from '../shared/ui/ui';
import s from '../features/member/Controls.module.css';
export { NoticeComparisonPage as ComparePage } from '../features/comparison/NoticeComparisonPage';
const fields: Record<string, string> = {
    title: '공고명',
    agency: '운영기관',
    region: '지역',
    type: '지원 유형',
    publishedDate: '공고일',
    startDate: '접수 시작',
    endDate: '접수 마감',
    summary: '공고 요약',
    source: '공식 근거',
    guidance: '안내 상태',
    lifecycle: '공식 조기 마감·취소 상태',
    questions: '질문',
    documents: '제출서류',
    schedules: '일정',
};
function value(v: Edition['facts'][string] | undefined, field?: string) {
    if (field === 'region' && typeof v === 'string')
        return recruitmentRegionLabel(v);
    if (v && typeof v === 'object' && !Array.isArray(v))
        return [
            v.state === 'NORMAL' ? '정상' : applicationStatuses[v.state].label,
            v.reason,
            v.evidence?.label,
            v.evidence?.checkedAt,
        ]
            .filter(Boolean)
            .join(' · ');
    return Array.isArray(v) ? v.join('\n') || '없음' : (v ?? '확인 필요');
}
export function VersionsPage() {
    const { noticeId = '' } = useParams(),
        [chosen, setChosen] = useState('');
    const detailReturn = useNoticeDetailReturn();
    const detailPath =
        detailReturn?.to ?? '/notices/' + encodeURIComponent(noticeId);
    const q = useQuery({
        queryKey: ['editions', noticeId],
        queryFn: () => memberApi.editions(noticeId),
    });
    const latest = q.data?.[0],
        previous = q.data?.slice(1),
        old =
            previous?.find((x) => x.version + '.' + x.revision === chosen) ??
            previous?.[0];
    const changes =
        old && latest
            ? Object.keys(fields).filter(
                  (k) =>
                      k in latest.facts &&
                      (k in old.facts || k === 'lifecycle') &&
                      value(old.facts[k], k) !== value(latest.facts[k], k),
              )
            : [];
    return (
        <>
            <Heading
                title="수정 공고 전후 비교"
                backTo={detailPath}
                backState={detailReturn?.state}
            >
                공개된 이력에서 변경된 항목만 표시합니다.
            </Heading>
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {latest && (
                <section className={s.section}>
                    <h2>{value(latest.facts.title)}</h2>
                    {old ? (
                        <>
                            <label>
                                이전 공개본
                                <select
                                    value={old.version + '.' + old.revision}
                                    onChange={(e) => setChosen(e.target.value)}
                                >
                                    {previous?.map((x, index) => (
                                        <option
                                            key={x.version + '.' + x.revision}
                                            value={x.version + '.' + x.revision}
                                        >
                                            {index === 0
                                                ? '바로 이전 공고'
                                                : `${index + 1}회 전 공고`}
                                        </option>
                                    ))}
                                </select>
                            </label>
                            {!changes.length && (
                                <p>선택한 두 공개본의 표시 항목은 같습니다.</p>
                            )}
                            {changes.map((k) => (
                                <div key={k}>
                                    <h3>{fields[k]}</h3>
                                    <dl className={s.difference}>
                                        <dt>이전</dt>
                                        <dd>{value(old.facts[k], k)}</dd>
                                        <dt>현재</dt>
                                        <dd>{value(latest.facts[k], k)}</dd>
                                    </dl>
                                </div>
                            ))}
                        </>
                    ) : (
                        <p>
                            비교할 이전 공개본이 없습니다. 이력 수집을 시작한
                            이후의 검수본을 비교할 수 있습니다.
                        </p>
                    )}
                    <Link to={detailPath} state={detailReturn?.state}>
                        현재 공식 근거 보기
                    </Link>
                </section>
            )}
        </>
    );
}
