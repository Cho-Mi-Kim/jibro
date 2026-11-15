import { call } from '../../shared/api/transport';

export interface CollectionScheduleStatus {
    enabled: boolean;
    configured: boolean;
    timezone: string;
    times: string[];
    nextRunAt: string | null;
    providerResumeAt: string | null;
    pendingRuns: number;
    message: string;
}

export const collectionScheduleApi = {
    get: () => call<CollectionScheduleStatus>('/admin/collections/schedule'),
};
