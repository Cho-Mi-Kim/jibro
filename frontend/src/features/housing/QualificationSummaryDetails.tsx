import { ConditionDetailText } from './ConditionDetailText';
import { EvidenceLink } from './EvidenceLink';
import { QualificationTables } from './QualificationTables';
import type { qualificationSummary } from './qualificationSummary';

/** Place each reviewed source beside the prose or score table it supports. */
export function QualificationSummaryDetails({
    summary,
}: {
    summary: ReturnType<typeof qualificationSummary>;
}) {
    return (
        <>
            {summary.tables ? (
                <QualificationTables
                    tables={summary.tables}
                    evidenceByTable={summary.scoreEvidence}
                />
            ) : summary.detail ? (
                <ConditionDetailText text={summary.detail} />
            ) : null}
            {summary.evidence && <EvidenceLink evidence={summary.evidence} />}
            {summary.reviewedDetails.map((detail, index) => (
                <ConditionDetailText
                    key={index}
                    text={detail.text}
                    structured={detail}
                />
            ))}
        </>
    );
}
