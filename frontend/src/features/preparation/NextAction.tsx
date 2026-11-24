import { useId } from 'react';
import { Link } from 'react-router';
import type { Preparation } from '../housing/types';
import { Disc } from '../../shared/ui/Disc';
import { Icon } from '../../shared/ui/Icon';
import { cx } from '../../shared/styles/classes';
import { useNoticeEntryState } from '../../shared/navigation/useNoticeEntryState';
import styles from './NextAction.module.css';

export function NextAction({
    p,
    compact = false,
}: {
    p: Preparation;
    compact?: boolean;
}) {
    const entryState = useNoticeEntryState();
    const reasonId = useId();
    const { nextAction } = p;
    const completed = !!nextAction.completed;
    const reason = completed
        ? nextAction.reason.split(/(?<=\.)\s+/).map((sentence, index) => (
              <span key={index} style={{ display: 'block' }}>
                  {sentence}
              </span>
          ))
        : nextAction.reason;
    const label = completed ? '준비 현황' : '지금 할 일';
    const icon = (
        <Disc
            name={completed ? 'check' : 'warning'}
            tone={completed ? 'green' : 'amber'}
        />
    );
    const path =
        nextAction.label === '확인하지 못한 조건 살펴보기'
            ? `${nextAction.path}?review=remaining`
            : nextAction.path;
    if (compact)
        return (
            <Link
                className={styles.compact}
                data-completed={completed}
                to={path}
                state={entryState}
                aria-describedby={reasonId}
            >
                {icon}
                <div>
                    <span>{label}</span>
                    <h2>{nextAction.label}</h2>
                    <p id={reasonId} className={styles.compactReason}>
                        {reason}
                    </p>
                </div>
                <Icon name="arrow" />
            </Link>
        );
    return (
        <section className={styles.card} data-completed={completed}>
            <div className={styles.icon}>{icon}</div>
            <p className={styles.label}>{label}</p>
            <h2>{nextAction.label}</h2>
            <p className={styles.reason}>{reason}</p>
            <Link
                className={`${cx('link-button wide')} ${styles.action}`}
                to={path}
                state={entryState}
            >
                {completed ? '완료한 일정 보기' : '준비 이어하기'}
            </Link>
        </section>
    );
}
