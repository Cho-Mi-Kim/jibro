import type { DocumentItem, DocumentTemplate } from '../housing/types';
import { ReviewIssues } from '../housing/ReviewIssues';
import { DocumentIssuanceLinks } from '../housing/DocumentIssuanceLinks';
import { EvidenceLink } from '../housing/EvidenceLink';
import { DocumentSummaryFacts } from '../housing/DocumentSummaryFacts';
import { documentSummary } from '../housing/documentSummary';
import type { OneTimeCheckResult } from './oneTimeCheckApi';
import styles from './OneTimeCheck.module.css';

export function OneTimeDocumentSummary({
    document,
    template,
    result,
}: {
    document: DocumentItem;
    template?: DocumentTemplate;
    result: OneTimeCheckResult;
}) {
    const summary = documentSummary(document, result, { template });
    const { criteria, issues, links, issuer, question } = summary;
    return (
        <section className={styles.document}>
            <h3>{document.title}</h3>
            <DocumentSummaryFacts summary={summary} />
            <ReviewIssues issues={issues} />
            <details>
                <summary
                    aria-label={`${document.title} 상세 안내·공식 근거 보기`}
                >
                    상세 안내·공식 근거 보기
                </summary>
                {criteria.length > 2 && (
                    <ul>
                        {criteria.map((criterion, i) => (
                            <li key={i}>{criterion}</li>
                        ))}
                    </ul>
                )}
                {question && <p>{question.reason}</p>}
                {issuer && <p>발급처 · {issuer}</p>}
                {document.method && <p>{document.method}</p>}
                <DocumentIssuanceLinks links={links} />
                <EvidenceLink evidence={document.evidence} />
            </details>
        </section>
    );
}
