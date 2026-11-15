import { useQuery } from '@tanstack/react-query';
import { demoMode } from '../../shared/api/transport';
import { Button } from '../../shared/ui/ui';
import { collectionScheduleApi } from './collectionScheduleApi';
import s from './CollectionSchedulePanel.module.css';

export function CollectionSchedulePanel() {
    const status = useQuery({
        queryKey: ['admin', 'collection-schedule'],
        queryFn: collectionScheduleApi.get,
        enabled: !demoMode,
        refetchInterval: 30000,
    });
    if (demoMode) return null;
    return (
        <section className={s.panel} aria-label="예약 수집">
            <div className={s.heading}>
                <h3>예약 수집</h3>
                {status.data && (
                    <span>
                        {!status.data.enabled
                            ? '꺼짐'
                            : status.data.configured
                              ? '사용 중'
                              : '수집 키 필요'}
                    </span>
                )}
            </div>
            {status.isPending && <p role="status">예약 설정을 불러옵니다.</p>}
            {status.error && (
                <div role="alert">
                    <p>{status.error.message}</p>
                    <Button onClick={() => void status.refetch()}>
                        다시 확인
                    </Button>
                </div>
            )}
            {status.data && (
                <>
                    <p>{status.data.message}</p>
                    <p>매일 00:00 · 06:00 · 09:00~18:00 매시간</p>
                    {status.data.nextRunAt && (
                        <p>
                            다음 예약 ·{' '}
                            {new Date(status.data.nextRunAt).toLocaleString(
                                'ko-KR',
                                {
                                    timeZone: 'Asia/Seoul',
                                },
                            )}
                        </p>
                    )}
                    {status.data.providerResumeAt && (
                        <p role="status">
                            공식 서버 요청 대기 ·{' '}
                            {new Date(
                                status.data.providerResumeAt,
                            ).toLocaleString('ko-KR', {
                                timeZone: 'Asia/Seoul',
                            })}
                            까지 새 수집도 기다립니다.
                        </p>
                    )}
                    {status.data.pendingRuns > 0 && (
                        <p>실행·재시도 대기 {status.data.pendingRuns}건</p>
                    )}
                    <p className={s.note}>
                        일시적 오류는 최대 3회 재시도합니다. 수집된 내용은 검수
                        후 공개합니다.
                    </p>
                </>
            )}
        </section>
    );
}
