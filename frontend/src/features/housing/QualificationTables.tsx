import { Fragment } from 'react';
import { EvidenceLink } from './EvidenceLink';
import type { Evidence } from './types';
import type { qualificationTables } from './parseQualificationTables';
import { alignedScoreRows } from './alignedScoreRows';
import { noticeSummaryText } from './summaryTone';
import styles from './QualificationTables.module.css';

export function QualificationTable({
    title,
    headers,
    rows,
}: {
    title: string;
    headers?: string[];
    rows: string[][];
}) {
    const groups: string[][][] = [];
    for (const row of rows) {
        const last = groups.at(-1);
        if (last && last[0][0] === row[0]) last.push(row);
        else groups.push([row]);
    }
    return (
        <table
            className={styles.table}
            aria-label={title}
            data-scored={headers?.at(-1) === '배점' || undefined}
        >
            {headers && <caption>{title}</caption>}
            {headers && (
                <thead>
                    <tr>
                        {headers.map((header) => (
                            <th key={header} scope="col">
                                {header}
                            </th>
                        ))}
                    </tr>
                </thead>
            )}
            {groups.map((group, index) => (
                <tbody key={index}>
                    {group.map(([label, ...values], rowIndex) => (
                        <tr key={rowIndex}>
                            {rowIndex === 0 && (
                                <th
                                    scope={
                                        group.length > 1 ? 'rowgroup' : 'row'
                                    }
                                    rowSpan={group.length}
                                >
                                    {label}
                                </th>
                            )}
                            {values.map((value, i) => (
                                <td key={i}>{noticeSummaryText(value)}</td>
                            ))}
                        </tr>
                    ))}
                </tbody>
            ))}
        </table>
    );
}

export function QualificationTables({
    tables,
    evidenceByTable,
}: {
    evidenceByTable?: Record<string, Evidence[]>;
    tables: NonNullable<ReturnType<typeof qualificationTables>>;
}) {
    const sources = (name: string) =>
        evidenceByTable?.[name]?.map((item) => (
            <EvidenceLink key={item.url} evidence={item} />
        ));
    return (
        <>
            {tables.shared.size > 0 && (
                <>
                    <table className={styles.table} data-comparison>
                        <caption>기본 배점</caption>
                        <thead>
                            <tr>
                                <th scope="col">구간</th>
                                {tables.types.map((type) => (
                                    <th key={type} scope="col">
                                        {type}
                                    </th>
                                ))}
                            </tr>
                        </thead>
                        {Array.from(tables.shared, ([category, byType]) => (
                            <tbody key={category}>
                                <tr>
                                    <th
                                        scope="rowgroup"
                                        colSpan={tables.types.length + 1}
                                    >
                                        {category}
                                    </th>
                                </tr>
                                {alignedScoreRows(
                                    tables.types.map(
                                        (type) => byType.get(type) ?? [],
                                    ),
                                ).map(([label, ...values]) => (
                                    <tr key={label}>
                                        <th scope="row">{label}</th>
                                        {values.map((value, i) => (
                                            <td key={i}>{value}</td>
                                        ))}
                                    </tr>
                                ))}
                            </tbody>
                        ))}
                    </table>
                    {sources('기본 배점')}
                </>
            )}
            {tables.bonusTables.map((table) => (
                <Fragment key={table.title}>
                    <QualificationTable {...table} />
                    {sources(table.title)}
                </Fragment>
            ))}
        </>
    );
}
