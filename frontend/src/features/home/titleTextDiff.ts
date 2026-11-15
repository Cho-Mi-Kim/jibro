export type TitlePart = {
    text: string;
    kind: 'shared' | 'summary' | 'original';
};

/** Align both forms so characters shared by the two titles stay in place. */
export function titleTextDiff(summary: string, original: string): TitlePart[] {
    const a = Array.from(summary);
    const b = Array.from(original);
    const lengths = Array.from(
        { length: a.length + 1 },
        () => new Uint32Array(b.length + 1),
    );
    for (let i = a.length - 1; i >= 0; i--) {
        for (let j = b.length - 1; j >= 0; j--) {
            lengths[i][j] =
                a[i] === b[j]
                    ? lengths[i + 1][j + 1] + 1
                    : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
        }
    }
    const parts: TitlePart[] = [];
    const add = (text: string, kind: TitlePart['kind']) => {
        const last = parts[parts.length - 1];
        if (last?.kind === kind) last.text += text;
        else parts.push({ text, kind });
    };
    let i = 0;
    let j = 0;
    while (i < a.length || j < b.length) {
        if (i < a.length && j < b.length && a[i] === b[j]) {
            add(a[i++], 'shared');
            j++;
        } else if (
            j < b.length &&
            (i === a.length || lengths[i][j + 1] >= lengths[i + 1][j])
        ) {
            add(b[j++], 'original');
        } else {
            add(a[i++], 'summary');
        }
    }
    return parts;
}
