import { Link } from 'react-router';
import type { Preparation } from '../housing/types';
import { Disc } from '../../shared/ui/HousingUI';
import { cx } from '../../shared/styles/classes';
import { useNoticeEntryState } from '../../shared/navigation/useNoticeEntryState';
import styles from './NextAction.module.css';

export function NextAction({ p }: { p: Preparation }) {
    const entryState = useNoticeEntryState();
    return (
        <section className={styles.card}>
            <div className={styles.icon}>
                <Disc name="warning" tone="amber" />
            </div>
            <p className={styles.label}>지금 할 일</p>
            <h2>{p.nextAction.label}</h2>
            <p className={styles.reason}>{p.nextAction.reason}</p>
            <Link
                className={`${cx('link-button wide')} ${styles.action}`}
                to={p.nextAction.path}
                state={entryState}
            >
                준비 방법 보기
            </Link>
        </section>
    );
}
