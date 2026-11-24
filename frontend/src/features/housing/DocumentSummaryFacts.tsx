import styles from './DocumentSummaryFacts.module.css';

export function DocumentSummaryFacts({
    summary,
}: {
    summary: {
        audience: string;
        method: string;
        audienceDetails?: string[];
        methodDetails?: string[];
    };
}) {
    return (
        <dl className={styles.facts}>
            <dt>제출 대상</dt>
            <dd>
                {summary.audience}
                <FactList items={summary.audienceDetails} />
            </dd>
            <dt>발급 방법</dt>
            <dd>
                {summary.method}
                <FactList items={summary.methodDetails} />
            </dd>
        </dl>
    );
}

function FactList({ items }: { items?: string[] }) {
    if (!items?.length) return null;
    return (
        <ul>
            {items.map((item, index) => (
                <li key={index}>{item}</li>
            ))}
        </ul>
    );
}
