import { Link } from 'react-router';
import type { FollowUpRecord } from '../housing/types';
import { followUpState, followUpResults } from '../housing/followUp';
import { Disc } from '../../shared/ui/Disc';
import { Icon } from '../../shared/ui/Icon';
import styles from './FollowUp.module.css';

export function FollowUpSummary({
    record,
    preparationId,
    historical = false,
}: {
    record?: FollowUpRecord | null;
    preparationId?: string;
    historical?: boolean;
}) {
    if (historical && !record) return null;
    const state = followUpState(record);
    const content = (
        <>
            <Disc
                name={state.done ? 'check' : 'calendar'}
                tone={state.done ? 'green' : 'gray'}
            />
            <div>
                <p className={styles.eyebrow}>
                    {historical ? '당시 신청 후 기록' : '신청 후 진행 상황'}
                </p>
                <h2>{state.label}</h2>
                {historical && record ? (
                    <ul className={styles.records}>
                        <li>
                            입주자 선정 결과 · {followUpResults[record.result]}
                        </li>
                        {record.documentsSubmitted && (
                            <li>서류 제출 완료로 기록함</li>
                        )}
                        {record.contractCompleted && (
                            <li>계약 완료로 기록함</li>
                        )}
                        {record.movedIn && <li>입주 완료로 기록함</li>}
                    </ul>
                ) : (
                    <p>{state.hint}</p>
                )}
            </div>
            {preparationId && <Icon name="arrow" />}
        </>
    );
    return preparationId ? (
        <Link
            className={`${styles.summary} ${styles.summaryLink}`}
            to={`/preparations/${preparationId}/follow-up`}
            aria-label="신청 후 진행 상황"
        >
            {content}
        </Link>
    ) : (
        <section
            className={styles.summary}
            aria-label={historical ? '당시 신청 후 기록' : '신청 후 진행 상황'}
        >
            {content}
        </section>
    );
}
