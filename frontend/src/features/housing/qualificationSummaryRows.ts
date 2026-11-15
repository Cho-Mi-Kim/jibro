import type { Notice } from './types';

export function qualificationSummaryRows(
    overview: Notice['qualificationSummary'],
) {
    return [
        {
            label: '신청 자격',
            text:
                overview?.eligibility ??
                '신청 자격은 조건 탭에서 항목별로 확인해 주세요.',
        },
        {
            label: '선정 순위',
            text:
                overview?.selection ??
                '선정 기준은 조건 탭과 공식 공고에서 확인해 주세요.',
        },
    ];
}
