import type { ReactNode } from 'react';
import { Disc } from '../../shared/ui/Disc';
import { InlineExplanation } from '../../shared/ui/ui';
import { cx } from '../../shared/styles/classes';
import styles from './NoticeDetail.module.css';
import summaryStyles from './NoticeSummaryCard.module.css';
import { useNoticeDetailSelection } from './useNoticeDetailSelection';
import { ConditionDetailText } from './ConditionDetailText';

export function NoticeSummaryCard({
    title,
    rows,
    explanationLabel,
    onToggle,
    children,
}: {
    title: string;
    rows: { label?: string | null; text: ReactNode; details?: string[] }[];
    explanationLabel: string;
    onToggle?: (open: boolean) => void;
    children: ReactNode;
}) {
    const { cardRef, onClick } = useNoticeDetailSelection();
    return (
        <section
            ref={cardRef}
            onClick={onClick}
            className={`${styles.summaryCard} ${summaryStyles.card} ${cx('explanation-card')}`}
        >
            <Disc name="file" />
            <h2>{title}</h2>
            {rows[0]?.label ? (
                <dl className={summaryStyles.rows}>
                    {rows.map(({ label, text }, index) => (
                        <div className={summaryStyles.row} key={index}>
                            <dt>{label}</dt>
                            <dd>{text}</dd>
                        </div>
                    ))}
                </dl>
            ) : (
                <div className={summaryStyles.rows}>
                    {rows.map(({ text }, index) => (
                        <p className={summaryStyles.paragraph} key={index}>
                            {text}
                        </p>
                    ))}
                </div>
            )}
            <InlineExplanation label={explanationLabel} onToggle={onToggle}>
                {rows.map((row, index) =>
                    row.details?.length ? (
                        <ConditionDetailText
                            key={index}
                            text={row.details.join('\n')}
                        />
                    ) : null,
                )}
                {children}
            </InlineExplanation>
        </section>
    );
}
