import type { ScoreCriterion } from './parseQualificationTables';

type Band = {
    unit: string;
    low: number;
    high: number;
    label: string;
    points: string;
};

function scoreBand({ criterion, points }: ScoreCriterion): Band | undefined {
    if (!points) return;
    const range = criterion.match(
        /^(만 )?(\d+)(년|세|명) (이상|미만)(?: (\d+)\3 미만)?$/u,
    );
    if (range) {
        const [, age, first, unit, boundary, last] = range;
        if ((age && unit !== '세') || (last && boundary !== '이상')) return;
        const low =
            boundary === '미만' ? (unit === '명' ? 1 : 0) : Number(first);
        const high =
            boundary === '미만'
                ? Number(first)
                : last
                  ? Number(last)
                  : Infinity;
        if (low < high)
            return {
                unit: (age ?? '') + unit,
                low,
                high,
                label: criterion,
                points,
            };
    }
    const count = criterion.match(/^(\d+)(?:~(\d+))?명$/u);
    if (count && Number(count[1]) <= Number(count[2] ?? count[1])) {
        return {
            unit: '명',
            low: Number(count[1]),
            high: Number(count[2] ?? count[1]) + 1,
            label: criterion,
            points,
        };
    }
}

function intervalLabel(low: number, high: number, unit: string) {
    if (unit === '명' && Number.isFinite(high)) {
        return high === low + 1 ? `${low}명` : `${low}~${high - 1}명`;
    }
    const prefix = unit === '만 세' ? '만 ' : '';
    const suffix = prefix ? '세' : unit;
    if (!Number.isFinite(high)) return `${prefix}${low}${suffix} 이상`;
    if (low === 0) return `${prefix}${high}${suffix} 미만`;
    return `${prefix}${low}${suffix} 이상 ${high}${suffix} 미만`;
}

function numericRows(columns: ScoreCriterion[][]) {
    let unit: string | undefined;
    const bands: Band[][] = [];
    for (const column of columns) {
        const sorted: Band[] = [];
        for (const entry of column) {
            const band = scoreBand(entry);
            if (!band || (unit && unit !== band.unit)) return;
            unit = band.unit;
            sorted.push(band);
        }
        sorted.sort((a, b) => a.low - b.low);
        if (sorted.some((band, i) => i > 0 && sorted[i - 1].high > band.low))
            return;
        bands.push(sorted);
    }
    if (!unit) return;
    const edges = [
        ...new Set(bands.flat().flatMap((b) => [b.low, b.high])),
    ].sort((a, b) => a - b);
    const rows: string[][] = [];
    for (let i = 1; i < edges.length; i++) {
        const low = edges[i - 1],
            high = edges[i];
        const matches = bands.map((column) =>
            column.find((b) => b.low <= low && high <= b.high),
        );
        if (matches.every((b) => !b)) continue;
        const label =
            matches.find((b) => b?.low === low && b.high === high)?.label ??
            intervalLabel(low, high, unit);
        rows.push([label, ...matches.map((b) => b?.points ?? '—')]);
    }
    return rows;
}

export function alignedScoreRows(columns: ScoreCriterion[][]) {
    // Legacy cells contain prose with the criterion and score already combined.
    if (columns.flat().every((entry) => entry.points === undefined)) {
        return [
            [
                '세부 기준',
                ...columns.map(
                    (column) =>
                        column.map((entry) => entry.criterion).join('\n') ||
                        '—',
                ),
            ],
        ];
    }
    const bands = numericRows(columns);
    if (bands) return bands;
    // Qualifiers such as income exceptions must match exactly; never infer a numeric band.
    const criteria = [
        ...new Set(columns.flat().map((entry) => entry.criterion)),
    ];
    return criteria.map((criterion) => [
        criterion,
        ...columns.map(
            (column) =>
                column
                    .filter((entry) => entry.criterion === criterion)
                    .map((entry) => entry.points ?? entry.criterion)
                    .join('\n') || '—',
        ),
    ]);
}
