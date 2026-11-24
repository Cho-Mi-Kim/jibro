import type { ReactNode } from 'react';
import { handleDisclosureCardClick } from '../../shared/ui/handleDisclosureCardClick';
import { documentSummary } from './documentSummary';
import { DocumentCard } from './DocumentCard';
import type { DocumentSummaryGroup, DocumentTemplate, Notice } from './types';
import styles from './CoreDocuments.module.css';

export function CoreDocuments({
    kind,
    notice,
    templates = notice.documentTemplates,
    renderDisclosure,
    factsExpanded,
}: {
    kind: 'common' | 'conditional';
    templates?: Notice['documentTemplates'];
    notice: Notice;
    factsExpanded?: boolean;
    renderDisclosure?: (
        item: DocumentSummaryGroup['items'][number],
        template: DocumentTemplate | undefined,
        summary: ReturnType<typeof documentSummary>,
    ) => ReactNode;
}) {
    const group = notice.documentSummary?.[kind];
    if (!group || (!group.items.length && !group.remainingCount)) return null;
    const byId = new Map(templates?.map((doc) => [doc.id, doc]));
    const additional =
        kind === 'common' || factsExpanded
            ? (group.remainingIds ?? []).flatMap((id) => byId.get(id) ?? [])
            : [];
    const remaining = group.remainingCount - additional.length;
    return (
        <>
            <ul className={styles.coreDocuments}>
                {[...group.items, ...additional].map((item) => {
                    const template = byId.get(item.id);
                    const summary = documentSummary(
                        template ?? { ...item, group: kind },
                        notice,
                    );
                    return (
                        <li
                            key={item.id}
                            data-result-card={
                                renderDisclosure ? true : undefined
                            }
                            onClick={
                                renderDisclosure
                                    ? handleDisclosureCardClick
                                    : undefined
                            }
                        >
                            {renderDisclosure ? (
                                renderDisclosure(item, template, summary)
                            ) : (
                                <DocumentCard
                                    title={item.title}
                                    summary={summary}
                                    groupOpen={factsExpanded ?? true}
                                />
                            )}
                        </li>
                    );
                })}
            </ul>
            {remaining > 0 && <small>외 {remaining}개</small>}
        </>
    );
}
