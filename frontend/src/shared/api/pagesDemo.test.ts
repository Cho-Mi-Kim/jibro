import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => {
    vi.unstubAllEnvs();
    vi.unstubAllGlobals();
    vi.resetModules();
});

it('배포용 시연은 개발 서버 없이 동작하고 실제 서버를 호출하지 않는다', async () => {
    vi.stubEnv('MODE', 'pages-demo');
    vi.stubEnv('DEV', false);
    vi.resetModules();
    const fetch = vi.fn(() => {
        throw new Error('실제 API 호출 금지');
    });
    vi.stubGlobal('fetch', fetch);
    const { call, demoMode } = await import('./transport');
    const { resetDemo } = await import('../../features/housing/demo');
    resetDemo();
    expect(demoMode).toBe(true);
    await call('/auth/login', 'POST', { email: 'demo@example.invalid' });
    const result = await call<{ items: unknown[] }>('/notices');
    expect(result.items.length).toBeGreaterThan(0);
    const prep = await call<{ id: string }>('/preparations', 'POST', {
        noticeId: '1',
        version: 1,
    });
    expect(prep.id).toBeTruthy();
    await call('/auth/logout', 'POST');
    await expect(call('/users/me')).rejects.toMatchObject({ status: 401 });
    expect(fetch).not.toHaveBeenCalled();
});

it('일반 운영 빌드는 환경 변수에 demo가 있어도 실제 API 모드를 유지한다', async () => {
    vi.stubEnv('MODE', 'production');
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_API_MODE', 'demo');
    vi.resetModules();
    expect((await import('./transport')).demoMode).toBe(false);
});
