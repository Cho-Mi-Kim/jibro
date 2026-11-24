import { officialReviewLabel } from '../housing/reviewIssueSelectors';
import type { conditionAnswerState } from '../housing/questions';
import type { IconName } from '../../shared/ui/Icon';

// The count includes unanswered and officially held questions as well as follow-up answers.
export const pendingAnswersLabel = '추가 확인';

export const conditionAnswerPresentation = {
    complete: { label: '답변 완료', icon: 'check', tone: 'green' },
    followUp: {
        label: pendingAnswersLabel + ' 필요',
        icon: 'warning',
        tone: 'amber',
    },
    unanswered: { label: '미응답', icon: 'clock', tone: 'gray' },
    official: { label: officialReviewLabel, icon: 'info', tone: 'amber' },
} as const satisfies Record<
    ReturnType<typeof conditionAnswerState>,
    {
        label: string;
        icon: IconName;
        tone: string;
    }
>;
