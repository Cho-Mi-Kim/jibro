import { call } from '../../shared/api/transport';

export type AttachmentCheckResult = {
    label: string;
    url: string;
    status: 'BASELINE' | 'UNCHANGED' | 'CHANGED' | 'FAILED' | 'DEFERRED';
    message: string;
    previousHash: string | null;
    currentHash: string | null;
    bytes: number | null;
};

export type AttachmentCheckRun = {
    id: string;
    status: 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'SUPERSEDED';
    startedAt: string;
    finishedAt: string | null;
    message: string;
    results: AttachmentCheckResult[];
};

function path(noticeId: string) {
    return (
        '/admin/reviews/' + encodeURIComponent(noticeId) + '/attachment-checks'
    );
}

export const attachmentChecksApi = {
    list: (noticeId: string) => call<AttachmentCheckRun[]>(path(noticeId)),
    run: (noticeId: string) => call<AttachmentCheckRun>(path(noticeId), 'POST'),
};
