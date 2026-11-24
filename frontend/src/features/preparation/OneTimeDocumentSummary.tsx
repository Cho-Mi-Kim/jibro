import type { DocumentItem, DocumentTemplate } from '../housing/types';
import { EvidenceLink } from '../housing/EvidenceLink';
import { DocumentCard } from '../housing/DocumentCard';
import { ConditionDetailText } from '../housing/ConditionDetailText';
import { documentSummary } from '../housing/documentSummary';
import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';
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
    const { question } = summary;
    return (
        <section
            data-result-card
            className={styles.document}
            onClick={handleDisclosureCardClick}
        >
            <DocumentCard title={document.title} heading="h3" summary={summary}>
                {question && <ConditionDetailText text={question.reason} />}
                {!summary.fullMethodShown && document.method && (
                    <ConditionDetailText text={document.method} />
                )}
                <EvidenceLink evidence={document.evidence} />
            </DocumentCard>
        </section>
    );
}
