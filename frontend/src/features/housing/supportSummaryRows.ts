export type SupportSummaryRow = { label: string | null; text: string };

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
        /대상(?:입니다|이며)/u.test(sentences[0]);
    const labels = ['지원 대상', '지원 내용', '함께 확인'];
    return sentences.map((text, index) => ({
        label: structured ? labels[index] : null,
        text,
    }));
}
