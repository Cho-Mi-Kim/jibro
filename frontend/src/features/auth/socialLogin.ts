import { safeReturn } from '../../shared/api/returnPath';

const returnToKey = 'jibro.returnTo';

export const socialLogin = {
    returnTo: (params: URLSearchParams) =>
        safeReturn(
            params.get('returnTo') ??
                (params.has('oauthError')
                    ? sessionStorage.getItem(returnToKey)
                    : '/my'),
        ),
    savedReturnTo: () => safeReturn(sessionStorage.getItem(returnToKey)),
    start: (provider: 'google' | 'kakao', returnTo: string) => {
        sessionStorage.setItem(returnToKey, safeReturn(returnTo));
        window.location.assign('/oauth2/authorization/' + provider);
    },
    clearReturnTo: () => sessionStorage.removeItem(returnToKey),
};
