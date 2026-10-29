import { afterEach, describe, expect, it, vi } from 'vitest';
import { call, clearToken, setToken } from './transport';
import { safeReturn } from './returnPath';
afterEach(() => {
    clearToken();
    vi.unstubAllGlobals();
});
describe('실제 서버 API 전송', () => {
    it('401 이후 한 번 재발급하고 같은 요청을 새 토큰으로 재시도', async () => {
        const fetch = vi
            .fn()
            .mockResolvedValueOnce(new Response('{}', { status: 401 }))
            .mockResolvedValueOnce(Response.json({ token: 'csrf' }))
            .mockResolvedValueOnce(Response.json({ accessToken: 'new-token' }))
            .mockResolvedValueOnce(Response.json({ id: '1' }));
        vi.stubGlobal('fetch', fetch);
        setToken('old-token');
        expect(await call('/users/me')).toEqual({ id: '1' });
        expect(fetch.mock.calls[2][1].headers['X-CSRF-TOKEN']).toBe('csrf');
        expect(fetch.mock.calls[2][1].headers.Authorization).toBeUndefined();
        expect(fetch.mock.calls[3][1].headers.Authorization).toBe(
            'Bearer new-token',
        );
    });
    it('빈 201 회원가입 응답을 정상 처리한다', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValueOnce(Response.json({ token: 'csrf' }))
                .mockResolvedValueOnce(new Response(null, { status: 201 })),
        );
        await expect(call('/auth/signup', 'POST', {})).resolves.toBeUndefined();
    });
    it('토큰 재발급 이후 업무 충돌을 인증 오류로 바꾸지 않는다', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValueOnce(new Response('{}', { status: 401 }))
                .mockResolvedValueOnce(Response.json({ token: 'csrf' }))
                .mockResolvedValueOnce(
                    Response.json({ accessToken: 'new-token' }),
                )
                .mockResolvedValueOnce(
                    Response.json(
                        { code: 'VERSION_CONFLICT', message: '다시 확인' },
                        { status: 409 },
                    ),
                ),
        );
        await expect(call('/preparations/1')).rejects.toMatchObject({
            status: 409,
            code: 'VERSION_CONFLICT',
        });
    });
    it('409의 업무 오류 코드를 보존한다', async () => {
        vi.stubGlobal(
            'fetch',
            vi
                .fn()
                .mockResolvedValue(
                    Response.json(
                        { message: '변경 확인 필요', code: 'STALE_VERSION' },
                        { status: 409 },
                    ),
                ),
        );
        await expect(call('/preparations/1')).rejects.toMatchObject({
            status: 409,
            code: 'STALE_VERSION',
        });
    });
    it('로그인 복귀 경로가 외부 사이트를 가리키지 않는다', () => {
        expect(safeReturn('https://example.com')).toBe('/preparations');
        expect(safeReturn('//example.com')).toBe('/preparations');
        expect(safeReturn('/\\example.com')).toBe('/preparations');
        expect(safeReturn('/notices/1')).toBe('/notices/1');
    });
});
