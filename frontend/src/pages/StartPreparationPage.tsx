import { noticeHomePath } from '../shared/navigation/homeNotice';
import { useCallback, useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import { useSession } from '../app/useSession';
import type { User } from '../features/housing/types';
import { api } from '../features/housing/api';
import { takeStartIntent } from '../features/preparation/startIntent';
import { choosePreparation } from '../features/preparation/useActivePreparation';
import { Button, Card, Heading, State } from '../shared/ui/ui';
import { useNotice } from '../shared/ui/housingHelpers';

export function StartPreparationPage() {
    const { noticeId = '' } = useParams();
    const { user } = useSession();
    const { pathname, search, state } = useLocation();
    const navigate = useNavigate();
    const client = useQueryClient();
    const params = new URLSearchParams(search);
    const requestedVersion = params.get('version');
    const returnHome = params.get('view') === 'home';
    const notice = useNotice(noticeId);
    const changed =
        !!requestedVersion &&
        notice.isSuccess &&
        notice.data.version !== Number(requestedVersion);
    const { mutate, isPending, error } = useMutation({
        mutationFn: async (version: number) => {
            const list = await api.preparations();
            const current = await api.notice(noticeId);
            if (!current.canStart || current.version !== version)
                throw new Error(
                    '공고 안내가 바뀌었습니다. 상세에서 현재 안내를 확인해 주세요.',
                );
            if (
                !user ||
                client.getQueryData<User | null>(['session'])?.id !== user.id
            ) {
                throw new Error('계정이 변경되었습니다. 다시 로그인해 주세요.');
            }
            const prepared = await api.start(noticeId, version);
            return { list, prepared };
        },
    });
    const start = useCallback(
        (version: number) => {
            if (!user) return;
            mutate(version, {
                onSuccess: ({ list, prepared }) => {
                    client.setQueryData(
                        ['preparation', user.id, prepared.id],
                        prepared,
                    );
                    client.setQueryData(
                        ['preparations', user.id],
                        [
                            prepared,
                            ...list.filter((item) => item.id !== prepared.id),
                        ],
                    );
                    void client.invalidateQueries({
                        queryKey: ['preparations', user.id],
                    });
                    choosePreparation(user.id, prepared.id);
                    navigate(
                        returnHome
                            ? noticeHomePath(prepared.noticeId)
                            : '/preparations/' +
                                  prepared.id +
                                  '/conditions/edit',
                        { replace: true, state },
                    );
                },
            });
        },
        [user, mutate, client, navigate, returnHome, state],
    );
    useEffect(() => {
        if (!notice.isSuccess || !user || !takeStartIntent(pathname + search))
            return;
        if (notice.data.canStart && !changed) start(notice.data.version);
    }, [notice.isSuccess, notice.data, user, pathname, search, start, changed]);
    return (
        <>
            <Heading title="공고 준비 시작" backTo={'/notices/' + noticeId}>
                {notice.data?.title}
            </Heading>
            <State
                loading={notice.isPending}
                error={notice.error}
                retry={() => void notice.refetch()}
            />
            {notice.isSuccess && (
                <Card>
                    <h2>
                        {notice.data.canStart
                            ? '이 공고로 준비를 시작할까요?'
                            : '지금은 준비를 시작할 수 없어요'}
                    </h2>
                    <p>
                        계정의 준비 목록을 불러오고 저장한 뒤 이 공고의 조건
                        질문으로 이동합니다.
                    </p>
                    {error && (
                        <p role="alert">
                            {error.message} 현재 준비 공고는 유지됩니다.{' '}
                            <Link to="/archive">보관함 확인</Link>
                        </p>
                    )}
                    {changed && (
                        <p role="alert">
                            선택한 뒤 공고 안내가 변경되었습니다. 상세에서 최신
                            버전을 확인하고 다시 시작해 주세요.
                        </p>
                    )}
                    {notice.data.canStart && !changed && (
                        <Button
                            disabled={isPending}
                            onClick={() => start(notice.data.version)}
                        >
                            {isPending
                                ? '준비 기록 확인 중…'
                                : error
                                  ? '다시 시도'
                                  : '준비 시작'}
                        </Button>
                    )}
                    <Link to={'/notices/' + noticeId}>공고 상세 보기</Link>
                </Card>
            )}
        </>
    );
}
