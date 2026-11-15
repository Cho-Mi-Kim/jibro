import { configureRequestAdapter, demoMode } from '../shared/api/transport';

export function configureTransport() {
    if (!demoMode) return;
    configureRequestAdapter(
        async <T>(path: string, method: string, body?: unknown) => {
            const { demoRequest } = await import('../features/housing/demo');
            return demoRequest<T>(path, method, body);
        },
    );
}
