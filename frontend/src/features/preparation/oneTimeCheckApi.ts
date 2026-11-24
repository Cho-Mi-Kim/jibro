import { call } from '../../shared/api/transport';
import { withReviewedConditions } from '../housing/reviewedConditionPresentation';
import type {
    Answer,
    DocumentItem,
    Question,
    ReviewIssue,
} from '../housing/types';

export interface OneTimeCheckResult {
    noticeId: string;
    title: string;
    version: number;
    questions: Question[];
    answers: Record<string, Answer>;
    documents: DocumentItem[];
    warnings: string[];
    reviewIssues?: ReviewIssue[];
}

export function oneTimeCheckPath(noticeId: string, version: number) {
    return (
        '/notices/' +
        encodeURIComponent(noticeId) +
        '/check/once?version=' +
        version
    );
}

export function preparationChoicePath(
    noticeId: string,
    version: number,
    destination: 'conditions' | 'home' = 'conditions',
) {
    const query = new URLSearchParams({ version: String(version) });
    if (destination === 'home') query.set('view', 'home');
    return '/notices/' + encodeURIComponent(noticeId) + '/check?' + query;
}

// Do not wrap this request in Query/Mutation: answers and results belong only
// to the mounted one-time screen and must not enter shared caches.
export const oneTimeCheckApi = {
    evaluate: (
        noticeId: string,
        version: number,
        answers: Record<string, Answer>,
    ) =>
        call<OneTimeCheckResult>(
            '/notices/' + encodeURIComponent(noticeId) + '/check-once',
            'POST',
            { version, answers },
        ).then(withReviewedConditions),
};
