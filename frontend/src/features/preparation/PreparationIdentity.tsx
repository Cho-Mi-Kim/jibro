import { Link } from 'react-router';
import { useNoticeEntryState } from '../../shared/navigation/useNoticeEntryState';
import type { Notice, Preparation } from '../housing/types';
import { currentPreparationSchedules } from '../housing/schedules';
import { Deadline, Status } from '../../shared/ui/HousingUI';
import { Icon } from '../../shared/ui/Icon';
import { preparationDeadlineLabel } from '../../shared/ui/housingHelpers';
import styles from './PreparationOverviewSummary.module.css';
export function PreparationIdentity({
    preparation: p,
    notice,
}: {
    preparation: Preparation;
    notice?: Notice;
}) {
    const entryState = useNoticeEntryState();
    return (
        <div className={styles.identity}>
            <div className={styles.status}>
                <Status tone="green">
                    {p.progress === 100 ? '준비 기록 완료' : '신청 준비 중'}
                </Status>
            </div>
            <div className={styles.title}>
                <Link
                    className={styles.identityLink}
                    to={'/notices/' + p.noticeId}
                    state={entryState}
                >
                    <h2>{p.title}</h2>
                    <span className={styles.noticeLink}>
                        <span>
                            {notice
                                ? notice.agency + ' · ' + notice.region
                                : '공고 정보 확인 중'}
                            {' · 안내 v' + p.version}
                        </span>
                        <Icon name="arrow" />
                    </span>
                </Link>
            </div>
            <div className={styles.deadline}>
                <Deadline
                    date={notice?.endDate}
                    dateLabel={preparationDeadlineLabel(
                        notice?.endDate,
                        currentPreparationSchedules(p, notice),
                    )}
                />
            </div>
        </div>
    );
}
