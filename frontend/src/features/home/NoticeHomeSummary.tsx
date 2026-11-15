import { NoticeLifecycleNotice } from '../housing/NoticeLifecycleNotice';
import { effectiveNoticeStatus } from '../housing/noticeLifecycle';
import { Link, useNavigate } from 'react-router';
import type { ReactNode } from 'react';
import type { Notice } from '../housing/types';
import { statusLabels } from '../housing/noticeLabels';
import { preparationChoicePath } from '../preparation/oneTimeCheckApi';
import { Button, Card } from '../../shared/ui/ui';
import { GuideBadge, NoticeDates } from '../housing/NoticeFacts';
import { Status } from '../../shared/ui/Status';
import { cx } from '../../shared/styles/classes';
import { HomeNoticeTitle } from '../home/HomeNoticeTitle';
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
                <Status
                    tone={
                        effectiveNoticeStatus(notice) === 'OPEN'
                            ? 'green'
                            : 'gray'
                    }
                >
                    {statusLabels[effectiveNoticeStatus(notice)]}
                </Status>
                <GuideBadge
                    state={notice.guideState}
                    partial={!!notice.reviewIssues?.length}
                />
            </div>
            <HomeNoticeTitle title={notice.title} />
            <p>
                {notice.agency} · {notice.region} · {notice.type}
            </p>
            <NoticeLifecycleNotice notice={notice} />
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
                        navigate(
                            preparationChoicePath(
                                notice.id,
                                notice.version,
                                'home',
                            ),
                        )
                    }
                >
                    이 공고로 준비 시작
                </Button>
            )}
            {children}
        </Card>
    );
}
