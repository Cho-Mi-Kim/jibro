import { call } from '../../shared/api/transport';

export interface CollectionResponseReceipt {
    id: string;
    runId: string;
    runAttempt: number;
    queryDate: string;
    page: number;
    requestedAt: string;
    receivedAt: string | null;
    httpStatus: number | null;
    sha256: string | null;
    redacted: boolean;
    bodyStatus: string;
    outcome: string;
}

export interface CollectionResponseBody {
    receipt: CollectionResponseReceipt;
    bodyJson: string | null;
}

export const collectionResponsesApi = {
    list: (runId: string) =>
        call<CollectionResponseReceipt[]>(
            `/admin/collections/${encodeURIComponent(runId)}/responses`,
        ),
    get: (runId: string, responseId: string) =>
        call<CollectionResponseBody>(
            `/admin/collections/${encodeURIComponent(runId)}/responses/${encodeURIComponent(responseId)}`,
        ),
};
