import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { socialLogin } from './socialLogin';

beforeEach(() => sessionStorage.clear());
afterEach(() => sessionStorage.clear());

it.each(['google', 'kakao'] as const)(
    'starts %s login at its authorization route with a saved return path',
    (provider) => {
        const assign = vi.fn();
        vi.stubGlobal('window', { location: { assign } });

        socialLogin.start(provider, '/notices/17?view=conditions#questions');

        expect(assign).toHaveBeenCalledWith(
            '/oauth2/authorization/' + provider,
        );
        expect(sessionStorage.getItem('jibro.returnTo')).toBe(
            '/notices/17?view=conditions#questions',
        );
    },
);

it('uses the explicit return path before a saved failed-login destination', () => {
    sessionStorage.setItem('jibro.returnTo', '/notices/old');
    expect(
        socialLogin.returnTo(
            new URLSearchParams(
                'oauthError=provider&returnTo=%2Fnotices%2Fnew',
            ),
        ),
    ).toBe('/notices/new');
});

it('does not reuse a previous attempt for an ordinary login visit', () => {
    sessionStorage.setItem('jibro.returnTo', '/notices/old');
    expect(socialLogin.returnTo(new URLSearchParams())).toBe('/my');
});

it.each([
    'https://example.invalid',
    '//example.invalid',
    '/\\example.invalid',
    '/auth/callback',
    '/login',
])('validates saved OAuth return paths: %s', (target) => {
    sessionStorage.setItem('jibro.returnTo', target);
    expect(
        socialLogin.returnTo(new URLSearchParams('oauthError=provider')),
    ).toBe('/preparations');
    expect(socialLogin.savedReturnTo()).toBe('/preparations');
});
