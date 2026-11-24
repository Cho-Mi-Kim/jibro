import { ApplicationBadge } from '../housing/NoticeStatusBadges';
import { AgencyHousingSummary } from '../housing/AgencyMark';
import { NoticeLifecycleNotice } from '../housing/NoticeLifecycleNotice';
import { Link, useNavigate } from 'react-router';
import type { ReactNode } from 'react';
import type { Notice } from '../housing/types';
import { preparationChoicePath } from '../preparation/oneTimeCheckApi';
import { Button, Card } from '../../shared/ui/ui';
import { NoticeDates } from '../housing/NoticeFacts';
import { cx } from '../../shared/styles/classes';
import { NoticeTitle } from '../housing/NoticeTitle';
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
                <ApplicationBadge notice={notice} />
            </div>
            <NoticeTitle title={notice.title} />
            <p>
                <AgencyHousingSummary notice={notice} />
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
