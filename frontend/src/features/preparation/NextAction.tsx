import { Link } from 'react-router';
import type { Preparation } from '../housing/types';
import { Disc } from '../../shared/ui/Disc';
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
                to={
                    p.nextAction.label === '확인하지 못한 조건 살펴보기'
                        ? `${p.nextAction.path}?review=remaining`
                        : p.nextAction.path
                }
                state={entryState}
            >
                준비 이어하기
            </Link>
        </section>
    );
}
