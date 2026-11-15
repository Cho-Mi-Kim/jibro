import styles from './DocumentSummaryFacts.module.css';

export function DocumentSummaryFacts({
    summary,
}: {
    summary: { audience: string; method: string };
}) {
    return (
        <dl className={styles.facts}>
            <dt>제출 대상</dt>
            <dd>{summary.audience}</dd>
            <dt>발급 방법</dt>
            <dd>{summary.method}</dd>
        </dl>
    );
}
