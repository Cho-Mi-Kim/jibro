import { PreparationIdentity } from './PreparationIdentity';
import type { Notice, Preparation } from '../housing/types';
import { House } from './PreparationProgress';
import { bindClasses } from '../../shared/styles/classes';
import { PreparationDates } from './PreparationUI';

import styles from './PreparationOverviewSummary.module.css';
import preparationClasses from './PreparationUI.module.css';

const cx = bindClasses(preparationClasses);

export function PreparationOverviewSummary({
    preparation: p,
    notice,
}: {
    preparation: Preparation;
    notice?: Notice;
}) {
    return (
        <section
            className={`${styles.summary} ${cx('explanation-card')}`}
            aria-label="선택한 신청 준비"
        >
            <PreparationIdentity preparation={p} notice={notice} />
            <PreparationDates notice={notice} preparation={p} />
            <div className={cx('preparation-progress')}>
                <House progress={p.progress} stage={p.houseStage} />
            </div>
        </section>
    );
}
