import styles from './ConditionSummary.module.css';
import { Fragment } from 'react';
import { EvidenceLink } from './EvidenceLink';
import type { StructuredConditionDetail } from './structuredConditionDetail';
import { QualificationTable, QualificationTables } from './QualificationTables';
import { noticeSummaryText } from './summaryTone';
import { generalConditionDetail } from './generalConditionDetail';
import { conciseNoticeText } from './conciseNoticeText';

export function ConditionDetailText({
    text,
    structured,
}: {
    text: string;
    structured?: StructuredConditionDetail;
}) {
    structured ??= generalConditionDetail(conciseNoticeText(text));
    if (!structured && !text.includes('\n• '))
        return <p className={styles.detail}>{conciseNoticeText(text)}</p>;

    const sections = structured?.sections ?? [{ text, evidence: [] }];
    return (
        <div className={styles.detailBlocks}>
            {sections.map((section, index) => (
                <Fragment key={index}>
                    <DetailParagraphs text={noticeSummaryText(section.text)} />
                    {section.table && <QualificationTable {...section.table} />}
                    {section.evidence.map((item) => (
                        <EvidenceLink key={item.url} evidence={item} />
                    ))}
                </Fragment>
            ))}
            {structured?.tables && (
                <QualificationTables
                    tables={structured.tables}
                    evidenceByTable={structured.tableEvidence}
                />
            )}
        </div>
    );
}

function DetailParagraphs({ text }: { text: string }) {
    const blocks = text.split(
        /(?:^|\n)((?:• |- )[^\n]+(?:\n(?:• |- )[^\n]+)*|\d+\. [^\n]+(?:\n\d+\. [^\n]+)*)/u,
    );
    return (
        <>
            {blocks.map((block, index) => {
                if (!block.trim()) return null;
                if (/^\d+\. /u.test(block))
                    return (
                        <ol key={index} className={styles.steps}>
                            {block.split('\n').map((line, row) => (
                                <li key={row} value={parseInt(line, 10)}>
                                    {line.slice(line.indexOf(' ') + 1)}
                                </li>
                            ))}
                        </ol>
                    );
                if (!/^(?:• |- )/u.test(block))
                    return block
                        .trim()
                        .split('\n')
                        .map((line, row) => (
                            <p key={index + '-' + row}>
                                {/^(기본 기준|예외·별도 기준|추가 확인)$/u.test(
                                    line,
                                ) ? (
                                    <strong>{line}</strong>
                                ) : (
                                    line
                                )}
                            </p>
                        ));
                return (
                    <ul key={index}>
                        {block.split('\n').map((line, row) => (
                            <li key={row}>{line.slice(2)}</li>
                        ))}
                    </ul>
                );
            })}
        </>
    );
}
