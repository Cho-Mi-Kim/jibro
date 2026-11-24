import type { Evidence } from './types';
import type { qualificationTables } from './parseQualificationTables';

type DetailTable = { title: string; headers: string[]; rows: string[][] };
export type DetailItem =
    string | Evidence[] | { table: DetailTable; evidence: Evidence[] };
type DetailSection = {
    text: string;
    evidence: Evidence[];
    table?: DetailTable;
};

export type StructuredConditionDetail = {
    preview?: string;
    text: string;
    sections: DetailSection[];
    tableEvidence?: Record<string, Evidence[]>;
    tables?: NonNullable<ReturnType<typeof qualificationTables>>;
};

export function sectioned(items: DetailItem[]): StructuredConditionDetail {
    const sections: DetailSection[] = [];
    let lines: string[] = [];
    for (const item of items) {
        if (typeof item === 'string') {
            lines.push(item);
        } else {
            const content = Array.isArray(item) ? { evidence: item } : item;
            sections.push({ text: lines.join('\n'), ...content });
            lines = [];
        }
    }
    if (lines.length) sections.push({ text: lines.join('\n'), evidence: [] });
    return {
        text: sections
            .flatMap(({ text, table }) =>
                table
                    ? [
                          text,
                          table.title,
                          ...table.rows.map((row) => row.join(' ')),
                      ]
                    : [text],
            )
            .join('\n'),
        sections,
    };
}
