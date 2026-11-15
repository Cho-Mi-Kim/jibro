import { useState, type FormEvent } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { useSession } from './useSession';
import { clearToken, demoMode } from '../../shared/api/transport';
import { Button } from '../../shared/ui/ui';
import {
    accountConnectionsApi as api,
    type ConnectionProvider,
    type AccountConnectionOverview,
} from './accountConnectionsApi';
import s from './AccountConnections.module.css';

const names = { google: 'Google', kakao: '카카오' };

function ConnectionAction({
    provider,
    disconnect,
    overview,
    onCancel,
}: {
    provider: ConnectionProvider;
    disconnect: boolean;
    overview: AccountConnectionOverview;
    onCancel: () => void;
}) {
    const client = useQueryClient();
    const [method, setMethod] = useState(
        overview.passwordLogin ? 'password' : 'email',
    );
    const [secret, setSecret] = useState('');
    const mail = useMutation({ mutationFn: api.requestProof });
    const action = useMutation({
        mutationFn: async () => {
            const proof =
                method === 'password'
                    ? { password: secret }
                    : { code: secret.trim() };
            if (disconnect) {
                await api.disconnect(provider, proof);
                clearToken();
                client.clear();
                api.returnToLogin();
            } else {
                const result = await api.start(provider, proof);
                api.openProvider(result.authorizationUrl);
            }
        },
    });
    function submit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        action.mutate();
    }
    return (
        <form
            className={s.proof}
            onSubmit={submit}
            aria-label={`${names[provider]} ${disconnect ? '연결 해제' : '연결'} 확인`}
        >
            <h3>
                {names[provider]} {disconnect ? '연결 해제' : '연결'}
            </h3>
            <p>
                {disconnect
                    ? '해제하면 모든 기기에서 로그아웃됩니다. 남은 로그인 수단으로 다시 로그인해 주세요.'
                    : '현재 계정의 본인 확인 후 연결할 소셜 계정을 선택합니다.'}
            </p>
            {overview.passwordLogin && (
                <fieldset className={s.methods} disabled={action.isPending}>
                    <legend>본인 확인 방법</legend>
                    {(['password', 'email'] as const).map((value) => (
                        <label key={value}>
                            <input
                                type="radio"
                                name="connection-proof-method"
                                value={value}
                                checked={method === value}
                                onChange={() => {
                                    setMethod(value);
                                    setSecret('');
                                    action.reset();
                                }}
                            />
                            {value === 'password'
                                ? '현재 비밀번호'
                                : '이메일 확인 코드'}
                        </label>
                    ))}
                </fieldset>
            )}
            {method === 'email' && (
                <>
                    <Button
                        type="button"
                        className="secondary"
                        disabled={
                            mail.isPending ||
                            overview.mailMode === 'unavailable'
                        }
                        onClick={() => mail.mutate()}
                    >
                        {mail.isPending ? '요청 중…' : '확인 코드 받기'}
                    </Button>
                    {overview.mailMode === 'unavailable' && (
                        <p>
                            이 환경에서는 확인 메일 발송이 설정되지 않았습니다.
                        </p>
                    )}
                    {mail.isSuccess && (
                        <p role="status">
                            {overview.mailMode === 'local'
                                ? '로컬 테스트 메일을 만들었습니다. .tools/account-mail 폴더에서 코드를 확인하세요.'
                                : '등록한 이메일로 확인 코드를 보냈습니다.'}{' '}
                            10분 안에 입력해 주세요.
                        </p>
                    )}
                    {mail.error && <p role="alert">{mail.error.message}</p>}
                </>
            )}
            <label className={s.field}>
                {method === 'password' ? '현재 비밀번호' : '이메일 확인 코드'}
                <input
                    type={method === 'password' ? 'password' : 'text'}
                    required
                    maxLength={method === 'password' ? 72 : 100}
                    autoComplete={
                        method === 'password'
                            ? 'current-password'
                            : 'one-time-code'
                    }
                    value={secret}
                    disabled={action.isPending}
                    onChange={(e) => setSecret(e.target.value)}
                />
            </label>
            <div className={s.actions}>
                <Button
                    type="button"
                    className="secondary"
                    onClick={onCancel}
                    disabled={action.isPending}
                >
                    취소
                </Button>
                <Button disabled={action.isPending || !secret.trim()}>
                    {action.isPending
                        ? '확인 중…'
                        : disconnect
                          ? '연결 해제'
                          : '확인 후 연결'}
                </Button>
            </div>
            {action.error && <p role="alert">{action.error.message}</p>}
        </form>
    );
}

export function AccountConnections() {
    const { user } = useSession();
    const [params] = useSearchParams();
    const [selected, setSelected] = useState<ConnectionProvider | null>(null);
    const enabled = !!user && !user.trial && !demoMode;
    const query = useQuery({
        queryKey: ['account-connections', user?.id],
        queryFn: api.overview,
        enabled,
    });
    if (!enabled) return null;
    const selectedProvider = query.data?.providers.find(
        (provider) => provider.id === selected,
    );
    return (
        <section
            className={s.section}
            aria-labelledby="account-connections-heading"
        >
            <h2 id="account-connections-heading">로그인 수단</h2>
            <p>기존 계정에 Google 또는 카카오 로그인을 연결할 수 있어요.</p>
            {params.get('connection') === 'success' && (
                <p role="status">로그인 수단을 연결했습니다.</p>
            )}
            {params.get('connection') === 'failed' && (
                <p role="alert">
                    연결하지 못했습니다. 만료된 요청이거나 이미 연결된 계정일 수
                    있어요. 다시 확인해 주세요.
                </p>
            )}
            {query.isPending && (
                <p role="status">연결 상태를 확인하고 있어요…</p>
            )}
            {query.error && (
                <>
                    <p role="alert">{query.error.message}</p>
                    <Button
                        className="secondary"
                        onClick={() => void query.refetch()}
                    >
                        다시 시도
                    </Button>
                </>
            )}
            {query.data && (
                <>
                    <ul className={s.providers}>
                        {query.data.passwordLogin && (
                            <li>
                                <div>
                                    <strong>이메일·비밀번호</strong>
                                    <span>사용 중</span>
                                </div>
                            </li>
                        )}
                        {query.data.providers.map((provider) => (
                            <li key={provider.id}>
                                <div>
                                    <strong>{names[provider.id]}</strong>
                                    <span>
                                        {provider.connected
                                            ? '연결됨'
                                            : provider.available
                                              ? '연결되지 않음'
                                              : '이 환경에서 연결 불가'}
                                    </span>
                                </div>
                                <Button
                                    type="button"
                                    className="secondary"
                                    disabled={
                                        provider.connected
                                            ? !provider.canDisconnect
                                            : !provider.available
                                    }
                                    aria-label={`${names[provider.id]} ${provider.connected ? '연결 해제' : '연결'}`}
                                    onClick={() => setSelected(provider.id)}
                                >
                                    {provider.connected ? '해제' : '연결'}
                                </Button>
                            </li>
                        ))}
                    </ul>
                    {query.data.providers.some(
                        (provider) =>
                            provider.connected && !provider.canDisconnect,
                    ) && (
                        <p>
                            마지막 로그인 수단은 해제할 수 없어요. 다른 수단을
                            먼저 연결해 주세요.
                        </p>
                    )}
                    {selectedProvider && (
                        <ConnectionAction
                            key={selectedProvider.id}
                            provider={selectedProvider.id}
                            disconnect={selectedProvider.connected}
                            overview={query.data}
                            onCancel={() => setSelected(null)}
                        />
                    )}
                </>
            )}
        </section>
    );
}
