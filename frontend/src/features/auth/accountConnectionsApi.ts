import { call } from '../../shared/api/transport';

export type ConnectionProvider = 'google' | 'kakao';
export type ConnectionProof = { password?: string; code?: string };
export interface AccountConnectionOverview {
    passwordLogin: boolean;
    mailMode: string;
    providers: {
        id: ConnectionProvider;
        connected: boolean;
        available: boolean;
        canDisconnect: boolean;
    }[];
}

export const accountConnectionsApi = {
    overview: () => call<AccountConnectionOverview>('/member/connections'),
    requestProof: () => call<void>('/member/connections/proof', 'POST'),
    start: (provider: ConnectionProvider, proof: ConnectionProof) =>
        call<{ authorizationUrl: string }>(
            `/member/connections/${provider}/start`,
            'POST',
            proof,
        ),
    disconnect: (provider: ConnectionProvider, proof: ConnectionProof) =>
        call<void>(`/member/connections/${provider}/disconnect`, 'POST', proof),
    openProvider: (authorizationUrl: string) => {
        if (
            !/^\/oauth2\/authorization\/(google|kakao)\?connect=1$/.test(
                authorizationUrl,
            )
        )
            throw new Error(
                '연결 주소를 확인하지 못했습니다. 다시 시도해 주세요.',
            );
        window.location.assign(authorizationUrl);
    },
    returnToLogin: () => window.location.assign('/login?returnTo=%2Fsettings'),
};
