import { ApiError, sessionExpiredEvent } from './client';
import { pagesDemo } from './environment';
const READ_TIMEOUT_MS = 8000;
let accessToken: string | null = null;
let refreshing: Promise<void> | null = null;
type RequestAdapter = <T>(
    path: string,
    method: string,
    body?: unknown,
) => Promise<T>;
let requestAdapter: RequestAdapter | null = null;

export function configureRequestAdapter(adapter: RequestAdapter) {
    requestAdapter = adapter;
}
export const demoMode =
    pagesDemo || (import.meta.env.DEV && import.meta.env.MODE === 'demo');
export function clearToken() {
    accessToken = null;
}
export function setToken(token: string) {
    accessToken = token;
}
function expire(token: string | null) {
    if (accessToken !== token || token === null) return;
    clearToken();
    window.dispatchEvent(new Event(sessionExpiredEvent));
}
async function fetchSafely(
    path: string,
    options: RequestInit,
    bounded: boolean,
) {
    try {
        return await fetch(path, {
            ...options,
            ...(bounded
                ? { signal: AbortSignal.timeout(READ_TIMEOUT_MS) }
                : {}),
        });
    } catch (error) {
        if (
            typeof error === 'object' &&
            error !== null &&
            'name' in error &&
            (error.name === 'TimeoutError' || error.name === 'AbortError')
        ) {
            throw new Error(
                '서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.',
                { cause: error },
            );
        }
        throw new Error(
            '서버에 연결하지 못했습니다. 입력 내용은 유지됩니다. 다시 시도해 주세요.',
            { cause: error },
        );
    }
}
async function csrf() {
    const response = await fetchSafely(
        '/api/v1/auth/csrf',
        {
            credentials: 'include',
        },
        true,
    );
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
    if (demoMode) {
        if (!requestAdapter)
            throw new Error('시연 요청 처리가 초기화되지 않았습니다.');
        return requestAdapter<T>(path, method, body);
    }
    const requestToken = accessToken;
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (accessToken && !path.startsWith('/auth/'))
        headers.Authorization = 'Bearer ' + accessToken;
    if (method !== 'GET') headers['X-CSRF-TOKEN'] = await csrf();
    const response = await fetchSafely(
        '/api/v1' + path,
        {
            method,
            headers,
            credentials: 'include',
            body: body === undefined ? undefined : JSON.stringify(body),
        },
        method === 'GET',
    );
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
        } catch (error) {
            if (error instanceof ApiError && error.status === 401)
                expire(requestToken);
            throw error;
        }
        // Preserve a retried request's 403/409/network error; it is not a login failure.
        return call<T>(path, method, body, false);
    }
    if (!response.ok) {
        if (response.status === 401 && !path.startsWith('/auth/'))
            expire(requestToken);
        const error = (await response.json().catch(() => ({}))) as {
            message?: string;
            code?: string;
        };
        throw new ApiError(response.status, error.message, error.code);
    }
    if (response.status === 204) return undefined as T;
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
}
