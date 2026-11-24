import { sectioned } from './structuredConditionDetail';
import {
    conciseNoticeText,
    summaryNarrationSentences,
} from './conciseNoticeText';
import type {
    DetailItem,
    StructuredConditionDetail,
} from './structuredConditionDetail';

// Keep source rows together while separating narration without cutting dates or decimals.
export function explanationLines(text: string): string[] {
    return text
        .trim()
        .split(/\n+/u)
        .flatMap((line) =>
            /^(?:[①-⑳]|\d+\.\s).*\s[|/]\s/u.test(line)
                ? [line]
                : summaryNarrationSentences(line),
        )
        .map((line) => line.trim())
        .filter(Boolean);
}

function cells(line: string) {
    return line.split(/\s+[|/]\s+/u).map((cell) => cell.trim());
}

function rowTable(lines: string[], title: string) {
    if (!lines.length || !lines.every((line) => cells(line).length > 1)) return;
    const rows = lines.map(cells);
    const first = rows[0];
    if (rows.length < 2 && !/^[①-⑳]|^\d+\. /u.test(first[0])) return;
    const header =
        rows.length > 1 &&
        /^(?:구분|유형|항목|가구원 수|순위)$/u.test(first[0]);
    // Keep irregular extracted rows intact in the second column; never guess a missing cell.
    if (!header || rows.some((row) => row.length !== first.length))
        return {
            title,
            headers: ['항목', '기준'],
            rows: rows.map(([label, ...values]) => [label, values.join(' / ')]),
        };
    return { title, headers: first, rows: rows.slice(1) };
}

function householdTable(lines: string[]) {
    const rows = lines.map((line) =>
        line.match(/^(\d+인(?: 가구)?)\s*[·:]\s*(.+)$/u),
    );
    if (rows.length < 2 || rows.some((row) => !row)) return;
    const amounts = rows.map((row) =>
        row![2].match(
            /^(\d+(?:\.\d+)?%) 기준 ([\d,.]+원) \/ (\d+(?:\.\d+)?%) 기준 ([\d,.]+원)$/u,
        ),
    );
    if (
        amounts.every(
            (amount) =>
                amount &&
                amount[1] === amounts[0]![1] &&
                amount[3] === amounts[0]![3],
        )
    )
        return {
            title: '가구원 수별 기준',
            headers: ['가구원 수', amounts[0]![1], amounts[0]![3]],
            rows: rows.map((row, index) => [
                row![1],
                amounts[index]![2],
                amounts[index]![4],
            ]),
        };
    return {
        title: '가구원 수별 기준',
        headers: ['가구원 수', '기준'],
        rows: rows.map((row) => [row![1], row![2]]),
    };
}

function rankTable(lines: string[]) {
    const rows = lines
        .flatMap((line) => line.split(/,\s*(?=\d+순위는 )/u))
        .map((line) => line.match(/^(\d+순위)는 (.+)$/u));
    if (rows.length < 2 || rows.some((row) => !row)) return;
    return {
        title: '순위별 신청 대상',
        headers: ['순위', '신청 대상'],
        rows: rows.map((row) => [row![1], row![2]]),
    };
}

function scoreTable(line: string, title: string) {
    const [label, ...criteria] = cells(line);
    if (!criteria.length || !/^[①-⑳]|^\d+\. /u.test(label)) return;
    const entries = criteria
        .flatMap((value) =>
            value.split(/\s+(?=[가나다라마바사아자차카타파하]\.\s*)/u),
        )
        .map((value) =>
            value.match(
                /^([가나다라마바사아자차카타파하]\.\s*.+?)\s*[:：]\s*(-?\d+점)$/u,
            ),
        );
    // A footnote, exception or ambiguous row stays in its original cell.
    if (entries.length < 2 || entries.some((entry) => !entry)) return;
    return {
        title,
        headers: ['항목', '기준', '배점'],
        rows: entries.map((entry) => [label, entry![1], entry![2]]),
    };
}

function classify(line: string) {
    if (
        /^(?:추가 확인|기관 확인|확인이 필요|요청받으면)|(?:기관|LH|접수처)에 (?:문의|확인)|증빙.*(?:필요|확인)/u.test(
            line,
        )
    )
        return '추가 확인';
    if (
        /^(?:다만|단,|예외|무주택 인정 예외|미성년.*예외|그 밖의 예외)|(?:경우|가구|대상|유형|주택)(?:에는|은|는)? .*(?:예외|면제|생략)/u.test(
            line,
        )
    )
        return '예외·별도 기준';
    return '';
}

function tableKind(line: string) {
    if (/^\d+인(?: 가구)?\s*[·:]/u.test(line)) return 'household';
    if (/^\d+순위는 /u.test(line)) return 'rank';
    if (cells(line).length > 1) return 'row';
    return null;
}

function compactFirst(line: string) {
    return line
        .replace(/^원칙적으로 /u, '원칙 · ')
        .replace(
            /^(원칙 · )?(공고일 기준 |신청일 기준 )?(만\s*)?(\d+)세 이상이 신청할 수 있어요\.$/u,
            '$1$2$3$4세부터 신청',
        )
        .replace(/(?:이 원칙이에요|이 기본입니다)\.$/u, '이 원칙')
        .replace(/(?:이어야 합니다|이어야 해요)\.$/u, '')
        .replace(/(?:을|를) 충족해야 (?:합니다|해요)\.$/u, ' 충족')
        .replace(/(?:을|를) 확인(?:하세요|해요|합니다)\.$/u, ' 확인')
        .replace(/(?:입니다|이에요|예요)\.$/u, '')
        .replace(/(?<=[다요])[.]$/u, '');
}

function tablePreview(detail: StructuredConditionDetail) {
    const table = detail.sections[0]?.table;
    if (!table) return;
    if (table.title === '가구원 수별 기준') return '가구원 수별 기준 확인';
    if (table.title === '순위별 신청 대상')
        return `${table.rows.map(([rank]) => rank).join(' · ')}별 신청 대상 확인`;
    const labels = [
        ...new Set(table.rows.map(([label]) => label.split(/\s*[*※]/u)[0])),
    ];
    if (labels.length <= 4 && labels.every((label) => label.length < 24))
        return labels.join(' · ');
    return '항목별 기준과 해당되는 예외 확인';
}

export function conciseConditionPreview(lines: string[], fallback: string) {
    const first = compactFirst(
        conciseNoticeText(
            fallback.length > 120 && /^[^①-⑳]+[①-⑳]/u.test(fallback)
                ? fallback.split(/[①-⑳]/u)[0].trim()
                : fallback,
        ),
    );
    const rest = lines.slice(1).join(' ');
    // A short preview points to later exceptions instead of presenting the first rule as universal.
    const hasException =
        /다만|예외|면제|생략|별도 (?:유형|기준)|만 적용|만 제출/u.test(rest);
    const hasRestriction =
        /(?:신청|계약|입주|제출).*(?:불가|무효|금지|안 돼|수 없)|감점/u.test(
            rest,
        );
    const notes = [
        hasException && !/예외|별도 기준/u.test(first)
            ? '예외·별도 기준 있음'
            : '',
        hasRestriction && !/제한|불가|무효|금지|감점/u.test(first)
            ? '제한 사항 확인'
            : '',
    ].filter(Boolean);
    return [first, ...notes].join(' · ');
}

/** Format only supplied criteria. This function neither approves facts nor creates page evidence. */
export function generalConditionDetail(
    text: string,
    title = '세부 기준',
): StructuredConditionDetail | undefined {
    const lines = explanationLines(text).map(conciseNoticeText);
    if (
        lines.length < 2 &&
        !rankTable(lines) &&
        !scoreTable(lines[0] ?? '', title) &&
        !tableKind(lines[0] ?? '') &&
        !/[①-⑳]/u.test(text)
    )
        return;
    const items: DetailItem[] = [];
    let index = 0;
    while (index < lines.length) {
        const line = lines[index];
        const score = scoreTable(line, title);
        if (score) {
            items.push({ table: score, evidence: [] });
            index++;
            continue;
        }
        const kindOfTable = tableKind(line);
        if (kindOfTable) {
            let end = index + 1;
            while (
                end < lines.length &&
                tableKind(lines[end]) === kindOfTable &&
                !scoreTable(lines[end], title)
            )
                end++;
            const group = lines.slice(index, end);
            const table =
                householdTable(group) ??
                rankTable(group) ??
                rowTable(group, title);
            if (table) {
                items.push({ table, evidence: [] });
                index = end;
                continue;
            }
        }
        const kind = classify(line);
        const group = [line];
        let end = index + 1;
        while (
            end < lines.length &&
            !tableKind(lines[end]) &&
            classify(lines[end]) === kind
        ) {
            group.push(lines[end]);
            end++;
        }
        const alreadyListed = group.some((value) =>
            /^(?:• |\d+\. )/u.test(value),
        );
        const body = (
            group.length > 1 && !alreadyListed
                ? group.map((value) => `• ${value}`).join('\n')
                : group.join('\n')
        ).replace(
            /\s+(?=[①-⑳]|[가나다라마바사아자차카타파하]\.\s|[※▶▸])/gu,
            '\n• ',
        );
        items.push(kind ? `${kind}\n${body}` : body, []);
        index = end;
    }
    const result = sectioned(items);
    const preview = tablePreview(result);
    return preview ? { ...result, preview } : result;
}
