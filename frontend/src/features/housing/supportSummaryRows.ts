import type { Notice } from './types';
import { samryeSupportSummary } from './samryeNoticeSummary';
import { noticeSummaryText } from './summaryTone';
import {
    conciseNoticeText,
    distinctSummarySentences,
} from './conciseNoticeText';

export type SupportSummaryRow = {
    label: string | null;
    text: string;
    details?: string[];
};

export function noticeSupportSummaryRows(notice: Notice): SupportSummaryRow[] {
    const reviewed = samryeSupportSummary(notice)?.rows;
    if (reviewed) return reviewed;
    const rows = supportSummaryRows(notice.summary);
    const grouped = groupLongSummary(rows) ?? rows;
    return grouped.map((row) => ({
        ...row,
        text: conciseNoticeText(noticeSummaryText(row.text, notice)),
        ...(row.details ? { details: row.details.map(conciseNoticeText) } : {}),
    }));
}

function groupLongSummary(
    rows: SupportSummaryRow[],
): SupportSummaryRow[] | undefined {
    if (rows.length <= 3 || rows.some((row) => row.label)) return;
    const audience: string[] = [];
    const support: string[] = [];
    const details: string[] = [];
    for (const { text, kind } of rows.flatMap(({ text }) =>
        supportClauses(text),
    )) {
        if (kind) {
            ({ audience, support, details })[kind].push(text);
            continue;
        }
        if (/준비는 이어갈 수|준비 기록은/u.test(text)) {
            details.push(text);
            continue;
        }
        if (
            /(?:무주택|소득·(?:총)?자산|모집대상|신청 자격|입주자격|자격 기준일|미성년|임대 신청|신청 가능|신청 허용|모집권역)/u.test(
                text,
            )
        )
            audience.push(text);
        else if (
            /(?:공급주택목록|공급목록|시세|보증금|월 임대료|최소\s*\d+\s*년 임대|분양전환(?:이 |은 |을 |형))/u.test(
                text,
            ) ||
            (!support.length && !audience.length)
        )
            support.push(text);
        else details.push(text);
    }
    // Without an explicit audience and support sentence, do not infer either from a title.
    if (!audience.length || !support.length) return;
    return [
        {
            label: '지원 대상',
            text: distinctSummarySentences(audience.join(' ')).join(' '),
        },
        {
            label: '지원 내용',
            text: distinctSummarySentences(support.join(' ')).join(' '),
        },
        ...(details.length
            ? [
                  {
                      label: '함께 확인',
                      text: '신청·서류·계약의 세부 안내를 확인해요.',
                      details,
                  },
              ]
            : []),
    ];
}

type SummaryClause = {
    text: string;
    kind?: 'audience' | 'support' | 'details';
};

// Split only complete, recognised clauses. Unfamiliar mixed rules stay together.
function supportClauses(text: string): SummaryClause[] {
    const combined = text.match(
        /^(.+?무주택세대구성원) 대상, 소득·자산 제한 없는 (순수 전세형), (정정 공급목록\s*\d+호)입니다\.$/u,
    );
    if (combined)
        return [
            {
                kind: 'audience',
                text: `${combined[1]}이 대상이며, 소득·자산 제한은 없어요.`,
            },
            { kind: 'support', text: `${combined[2]} · ${combined[3]}` },
        ];
    const dates = text.match(
        /^자격 기준일은 ([\d.]+)이며 정정 게시일 ([\d/]+)과 구분합니다\.$/u,
    );
    if (dates)
        return [
            { kind: 'audience', text: `자격 기준일은 ${dates[1]}이에요.` },
            {
                kind: 'details',
                text: `정정 게시일 ${dates[2]}은 자격 기준일과 달라요.`,
            },
        ];
    const units = text.match(
        /^기준일은 ([\d.]+), 공급목록\s*(\d+)호·실사용\s*(\d+(?:\.\d+)?)㎡ 이상 군을 확인했습니다\.$/u,
    );
    if (units)
        return [
            { kind: 'audience', text: `기준일은 ${units[1]}이에요.` },
            {
                kind: 'support',
                text: `${units[2]}호 · 실사용 ${units[3]}㎡ 이상`,
            },
        ];
    const minorAndRegion = text.match(
        /^이번 공고의 (.+?신청 허용)과 미성년 예외를 적용하고, (.+)$/u,
    );
    if (minorAndRegion)
        return [
            {
                kind: 'audience',
                text: `${minorAndRegion[1]} · 미성년자는 일부 예외가 있어요.`,
            },
            { kind: 'details', text: minorAndRegion[2] },
        ];
    const minor = text.match(/^미성년 예외·(.+)$/u);
    if (minor)
        return [
            { kind: 'audience', text: '미성년자는 일부 예외가 있어요.' },
            { kind: 'details', text: minor[1] },
        ];
    const income = text.match(
        /^소득·자산과 관계없이 입주 신청 가능하다는 첨부 Q&A를 확인했고 (.+)$/u,
    );
    if (income)
        return [
            {
                kind: 'audience',
                text: '소득·자산과 관계없이 신청할 수 있어요.',
            },
            { kind: 'details', text: income[1] },
        ];
    return [{ text }];
}

export function summarySentences(summary: string): string[] {
    // Split Korean sentence endings, not decimal values or dotted dates.
    return summary
        .trim()
        .split(/(?<=[다요][.!?])\s+/u)
        .filter(Boolean);
}

/** Present the existing audience → support → note copy without rewriting facts. */
export function supportSummaryRows(summary: string): SupportSummaryRow[] {
    const labeled = summary
        .trim()
        .match(
            /^지원 대상\s*[:：]\s*(.+?)\s+지원 내용\s*[:：]\s*(.+?)(?:\s+함께 확인\s*[:：]\s*(.+))?$/su,
        );
    if (labeled) {
        const [, audience, support, note] = labeled;
        return [
            { label: '지원 대상', text: audience.trim() },
            { label: '지원 내용', text: support.trim() },
            ...(note ? [{ label: '함께 확인', text: note.trim() }] : []),
        ];
    }
    const sentences = summarySentences(summary);
    const structured =
        sentences.length >= 2 &&
        sentences.length <= 3 &&
        /대상(?:입니다|이며|이에요)/u.test(sentences[0]);
    const labels = ['지원 대상', '지원 내용', '함께 확인'];
    return sentences.map((text, index) => ({
        label: structured ? labels[index] : null,
        text,
    }));
}
