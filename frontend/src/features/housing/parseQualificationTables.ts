import type { Notice } from './types';

type Overview = Notice['qualificationSummary'];
export type ScoreCriterion = { criterion: string; points?: string };

/** Read explicitly tabulated, reviewed copy. Preserve the original prose for other notices. */
export function qualificationTables(overview: Overview) {
    if (!overview?.detail || !overview.evidence?.url || overview.reviewRequired)
        return null;
    const [selection, ...lines] = overview.detail.trim().split(/\r?\n/);
    if (selection !== overview.selection) return null;
    const divider = lines.indexOf('배점 기준');
    if (divider < 1) return null;
    const facts: string[][] = [];
    for (const line of lines.slice(0, divider)) {
        const match = line.match(/^([^:]+): (.+)$/u);
        if (!match) return null;
        facts.push([match[1], match[2]]);
    }
    const [head, ...scores] = lines
        .slice(divider + 1)
        .map((line) => line.split(' | '));
    if (
        !head ||
        head.length < 2 ||
        !scores.length ||
        [head, ...scores].some(
            (row) =>
                row.length !== head.length || row.some((cell) => !cell.trim()),
        )
    )
        return null;
    const detailed = head.join(' | ') === '유형 | 항목 | 세부 기준 | 배점';
    const types = detailed
        ? [...new Set(scores.map((row) => row[0]))]
        : head.slice(1);
    const shared = new Map<string, Map<string, ScoreCriterion[]>>();
    const bonuses = new Map<string, string[][]>();

    function add(
        type: string,
        category: string,
        criterion: string,
        points?: string,
    ) {
        if (['가점', '장애 정도', '사회취약계층'].includes(category)) {
            const rows = bonuses.get(type) ?? [];
            rows.push(
                points === undefined
                    ? [category, criterion]
                    : [category, criterion, points],
            );
            bonuses.set(type, rows);
            return;
        }
        const byType =
            shared.get(category) ?? new Map<string, ScoreCriterion[]>();
        const entries = byType.get(type) ?? [];
        entries.push({ criterion, points });
        byType.set(type, entries);
        shared.set(category, byType);
    }
    for (const row of scores) {
        if (detailed) add(row[0], row[1], row[2], row[3]);
        else types.forEach((type, i) => add(type, row[0], row[i + 1]));
    }
    return {
        facts: [...facts, ['최종 선정', selection]],
        evidence: overview.evidence,
        types,
        shared,
        bonusTables: Array.from(bonuses, ([type, rows]) => ({
            title: type + ' 가점',
            headers: detailed ? head.slice(1) : [head[0], '배점 기준'],
            rows,
        })),
    };
}
