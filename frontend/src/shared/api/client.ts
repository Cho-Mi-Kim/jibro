export class ApiError extends Error {
    readonly status: number;

    constructor(status: number) {
        super(`요청을 처리하지 못했습니다. (HTTP ${status})`);
        this.name = 'ApiError';
        this.status = status;
    }
}

export async function request<T>(
    path: string,
    options?: RequestInit,
): Promise<T> {
    const response = await fetch(path, options);
    if (!response.ok) {
        throw new ApiError(response.status);
    }
    if (response.status === 204) {
        return undefined as T;
    }
    return (await response.json()) as T;
}
