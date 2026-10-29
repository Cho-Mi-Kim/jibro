import { ApiError } from './client';
let accessToken: string | null = null;
let refreshing: Promise<void> | null = null;
export const demoMode =
    import.meta.env.DEV && import.meta.env.VITE_API_MODE === 'demo';
export function clearToken() {
    accessToken = null;
}
export function setToken(token: string) {
    accessToken = token;
}
async function csrf() {
    const response = await fetch('/api/v1/auth/csrf', {
        credentials: 'include',
    });
    if (!response.ok) throw new ApiError(response.status);
    const body = (await response.json()) as { token: string };
    return body.token;
}
export async function call<T>(
    path: string,
    method = 'GET',
    body?: unknown,
    retry = true,
): Promise<T> {
    if (demoMode)
        return (await import('../../features/housing/demo')).demoRequest<T>(
            path,
            method,
            body,
        );
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (accessToken) headers.Authorization = 'Bearer ' + accessToken;
    if (method !== 'GET') headers['X-CSRF-TOKEN'] = await csrf();
    let response: Response;
    try {
        response = await fetch('/api/v1' + path, {
            method,
            headers,
            credentials: 'include',
            body: body === undefined ? undefined : JSON.stringify(body),
        });
    } catch {
        throw new Error(
            '서버에 연결하지 못했습니다. 입력 내용은 유지됩니다. 다시 시도해 주세요.',
        );
    }
    if (response.status === 401 && retry && !path.startsWith('/auth/')) {
        refreshing ??= call<{ accessToken: string }>(
            '/auth/refresh',
            'POST',
            undefined,
            false,
        )
            .then((x) => setToken(x.accessToken))
            .finally(() => {
                refreshing = null;
            });
        try {
            await refreshing;
            return await call<T>(path, method, body, false);
        } catch {
            clearToken();
            throw new ApiError(401);
        }
    }
    if (!response.ok) {
        const error = (await response.json().catch(() => ({}))) as {
            message?: string;
            code?: string;
        };
        throw new ApiError(response.status, error.message, error.code);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
}
