import type { Guide } from '../housing/types';
import { EvidenceLink } from '../housing/EvidenceLink';
import { GuideFreshnessNotice } from './GuideFreshnessNotice';
import styles from './GuideAnswer.module.css';

/** Separate Korean sentences without splitting dates, decimals or list numbers. */
function sentenceLines(text: string) {
    return text.replace(/([가-힣][.!?][’”"')\]]*)[ \t]+(?=\S)/gu, '$1\n');
}

export function GuideAnswer({ guide }: { guide: Guide }) {
    const paragraphs = guide.body.trim().split(/\n\s*\n/);
    return (
        <div className={styles.answer}>
            <GuideFreshnessNotice freshness={guide.freshness} />
            <div className={styles.body}>
                {paragraphs.map((paragraph, index) => {
                    const lines = paragraph
                        .split('\n')
                        .map((line) => line.trim());
                    if (lines.every((line) => /^-\s+/.test(line))) {
                        return (
                            <ul key={index}>
                                {lines.map((line, item) => (
                                    <li key={item}>
                                        {sentenceLines(
                                            line.replace(/^-\s+/, ''),
                                        )}
                                    </li>
                                ))}
                            </ul>
                        );
                    }
                    if (
                        lines.length > 1 &&
                        lines.every((line, item) =>
                            new RegExp(`^${item + 1}[.)]\\s+`).test(line),
                        )
                    ) {
                        return (
                            <ol key={index}>
                                {lines.map((line, item) => (
                                    <li key={item}>
                                        {sentenceLines(
                                            line.replace(/^\d+[.)]\s+/, ''),
                                        )}
                                    </li>
                                ))}
                            </ol>
                        );
                    }
                    return <p key={index}>{sentenceLines(paragraph)}</p>;
                })}
            </div>
            {guide.scope && (
                <p className={styles.scope}>
                    <strong>적용 범위</strong>
                    {sentenceLines(guide.scope)}
                </p>
            )}
            <div className={styles.sources}>
                <strong>공식 자료</strong>
                {[guide.evidence, ...(guide.additionalEvidence ?? [])].map(
                    (evidence) => (
                        <EvidenceLink
                            key={`${evidence.url}:${evidence.label}`}
                            evidence={evidence}
                        />
                    ),
                )}
            </div>
        </div>
    );
}
