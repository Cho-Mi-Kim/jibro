import { summarySentences } from './supportSummaryRows';
import { conciseNoticeText } from './conciseNoticeText';

function instructionLines(text: string) {
    return text
        .split(/\s+[•*※]\s*|\s+-\s+(?=[가-힣]|\d+\s*(?:세|순위|명))/u)
        .flatMap((part) => summarySentences(part))
        .flatMap((part) =>
            part.split(/(?<=(?:발급|제외|서명|제출|날인)\.)\s+/u),
        )
        .map((part) => conciseNoticeText(part.replace(/^[•*※]\s*/u, '').trim()))
        .filter(Boolean);
}

// Keep every substantive instruction. The first line is a quick guide; the rest
// remains visible as separate items when the document card is open.
export function documentInstructions(method: string) {
    const blocks = method.split(
        /(?:^|[•※*])\s*\((제출대상|발급방법|작성방법|동의방법|제출방법|제출시점|주의)\)\s*/u,
    );
    const audience: string[] = [];
    const parts = instructionLines(blocks[0]);
    for (let index = 1; index < blocks.length; index += 2) {
        const label = blocks[index];
        let content = blocks[index + 1].trim();
        if (label === '제출대상') {
            // This explicit instruction is appended after the last recipient in
            // extracted LH forms; it belongs to issuance, not eligibility.
            const issuance = content.match(
                /이 공고에 제출할 때는 주민등록번호 13자리가 모두 보이게 발급받으세요\.$/u,
            )?.[0];
            if (issuance) {
                content = content.slice(0, -issuance.length).trim();
                parts.push(issuance);
            }
            audience.push(
                ...instructionLines(content).filter(
                    (line) => line !== '아래 해당자',
                ),
            );
        } else {
            parts.push(
                ...instructionLines(content).map(
                    (line) => `${label} · ${line}`,
                ),
            );
        }
    }
    const specific = parts.filter(
        (part) =>
            part !== '공고일 이후 발급 기준을 확인해요.' &&
            part !== '공고의 해당자 제출 기준을 확인해요.',
    );
    const lines = specific.length ? specific : parts.slice(0, 1);
    const [first, next, ...rest] = lines;
    if (
        first &&
        next &&
        /미만|제외|생략|열람용/u.test(next) &&
        first.length + next.length < 85
    )
        return { instruction: first + ' ' + next, details: rest, audience };
    return { instruction: first, details: lines.slice(1), audience };
}
