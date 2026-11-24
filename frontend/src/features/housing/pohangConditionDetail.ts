import type { Question } from './types';
import { sectioned } from './structuredConditionDetail';
import { pohangCoreDetails } from './pohangCoreConditionDetail';
import { pohangDocumentDetails } from './pohangDocumentConditionDetail';
import { pohangReviewedQuestions } from './pohangReviewedQuestions';
import { matchesSpecializedCondition } from './specializedConditionIdentity';

export function pohangReviewedEntry(question: Question) {
    const entry = pohangReviewedQuestions[question.id];
    if (!entry || !matchesSpecializedCondition(question, 'pohang')) return;
    return entry;
}

export function pohangConditionHeading(question: Question) {
    return pohangReviewedEntry(question)?.heading;
}

export function pohangConditionDetail(question: Question) {
    if (!pohangReviewedEntry(question)) return;
    const detail =
        pohangCoreDetails[question.id] ?? pohangDocumentDetails[question.id];
    return detail && { ...sectioned(detail.items), preview: detail.preview };
}
