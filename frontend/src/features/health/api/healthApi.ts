import { request } from '../../../shared/api/client';

export interface HealthResponse {
    status: 'UP' | 'DOWN';
    database: 'UP' | 'DOWN';
}

export function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
    return request<HealthResponse>('/api/v1/health', { signal });
}
