export type NoticeDetailTab =
    'summary' | 'conditions' | 'documents' | 'schedule';
export const noticeDetailTabs: { value: NoticeDetailTab; label: string }[] = [
    { value: 'summary', label: '핵심 요약' },
    { value: 'conditions', label: '조건' },
    { value: 'documents', label: '제출서류' },
    { value: 'schedule', label: '일정' },
];
