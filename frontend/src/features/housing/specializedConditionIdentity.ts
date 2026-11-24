import type { Question } from './types';
import {
    matchesReviewedCondition,
    type ReviewedConditionIdentity,
} from './reviewedConditionIdentity';
import contracts from './reviewedConditions/specialized-contracts.json';

// Frozen reviewed input contracts for the four notices with bespoke tables and applicant flows.
type Notice = 'osan' | 'gyeongju' | 'samrye' | 'pohang';
const identities = contracts as (ReviewedConditionIdentity & {
    notice: Notice;
})[];
export function matchesSpecializedCondition(
    question: Question,
    notice: Notice,
) {
    return identities.some(
        (entry) =>
            entry.notice === notice &&
            matchesReviewedCondition(question, entry),
    );
}
