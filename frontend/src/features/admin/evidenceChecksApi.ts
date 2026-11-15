import { call } from '../../shared/api/transport';

export type EvidenceCheckResult = {
    kind: string;
    label: string;
    url: string | null;
    checkedAt: string | null;
    status:
        | 'REACHABLE'
        | 'UNAVAILABLE'
        | 'MANUAL'
        | 'MISSING'
        | 'UNSUPPORTED'
        | 'UNCONFIRMED'
        | 'DEFERRED';
    httpStatus: number | null;
};

export type EvidenceCheckReport = {
    revision: number;
    version: number;
    checkedAt: string;
    results: EvidenceCheckResult[];
};

export const evidenceChecksApi = {
    run: (noticeId: string, revision: number) =>
        call<EvidenceCheckReport>(
            '/admin/reviews/' +
                encodeURIComponent(noticeId) +
                '/evidence-checks',
            'POST',
            { revision },
        ),
};
