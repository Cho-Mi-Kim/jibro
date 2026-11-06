import { Link, useNavigate } from 'react-router';
import type { ReactNode } from 'react';
import type { Notice } from '../housing/types';
import { statusLabels } from '../housing/noticeLabels';
import { startPath } from '../preparation/startIntent';
import { Button, Card } from '../../shared/ui/ui';
import { GuideBadge, NoticeDates, Status } from '../../shared/ui/HousingUI';
import { cx } from '../../shared/styles/classes';
import { HomeNoticeTitle } from '../../shared/ui/HomeNoticeTitle';
import { useNoticeEntryState } from '../../shared/navigation/useNoticeEntryState';

export function NoticeHomeSummary({
    notice,
    children,
}: {
    notice: Notice;
    children?: ReactNode;
}) {
    const navigate = useNavigate();
    const entryState = useNoticeEntryState();
    return (
        <Card>
            <div className={cx('row')}>
                <Status tone={notice.status === 'OPEN' ? 'green' : 'gray'}>
                    {statusLabels[notice.status]}
                </Status>
                <GuideBadge state={notice.guideState} />
            </div>
            <h2>
                <HomeNoticeTitle title={notice.title} type={notice.type} />
            </h2>
            <p>
                {notice.agency} · {notice.region} · {notice.type}
            </p>
            <NoticeDates notice={notice} />
            <p>
                {notice.canStart
                    ? '준비를 시작하면 이 공고의 준비도와 다음 할 일을 홈에서 확인할 수 있어요.'
                    : notice.status === 'CLOSED'
                      ? '접수가 끝난 공고예요. 일정과 공식 근거는 계속 확인할 수 있어요.'
                      : '아직 준비를 시작할 수 없는 공고예요. 일정과 안내 상태를 먼저 확인하세요.'}
            </p>
            <Link
                className={cx('text-button')}
                to={'/notices/' + notice.id}
                state={entryState}
            >
                공고 상세와 공식 근거 보기
            </Link>
            {notice.canStart && (
                <Button
                    className="wide"
                    onClick={() =>
                        navigate(startPath(notice.id, notice.version, 'home'))
                    }
                >
                    이 공고로 준비 시작
                </Button>
            )}
            {children}
        </Card>
    );
}
