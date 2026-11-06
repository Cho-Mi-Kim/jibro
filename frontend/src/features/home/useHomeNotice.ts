import { useEffect } from 'react';
import { useSearchParams } from 'react-router';
import { useSession } from '../../app/useSession';
import { useNotice } from '../../shared/ui/housingHelpers';
import {
    useActivePreparation,
    choosePreparation,
} from '../preparation/useActivePreparation';
import {
    rememberedNotice,
    rememberNotice,
} from '../../shared/navigation/homeNotice';

export function useHomeNotice() {
    const session = useSession();
    const owner = session.user?.id;
    const preparations = useActivePreparation();
    const [params, setParams] = useSearchParams();
    const requested = params.get('notice') || null;
    const noticeId =
        requested ?? (session.loading ? null : rememberedNotice(owner));
    const current = preparations.active;
    const active =
        !noticeId || current?.noticeId === noticeId
            ? current
            : preparations.query.data?.find((p) => p.noticeId === noticeId);
    const notice = useNotice(noticeId ?? undefined);

    useEffect(() => {
        if (!requested || session.loading || session.error) return;
        if (owner && active?.noticeId === requested)
            choosePreparation(owner, active.id);
        else if (notice.data?.id === requested)
            rememberNotice(requested, owner);
    }, [
        requested,
        owner,
        active?.id,
        active?.noticeId,
        notice.data?.id,
        session.loading,
        session.error,
    ]);

    function selectNotice(id: string | null) {
        if (!id) rememberNotice(null, owner);
        const next = new URLSearchParams(params);
        if (id) next.set('notice', id);
        else next.delete('notice');
        setParams(next, { replace: true });
    }

    return {
        preparations: preparations.query,
        active,
        noticeId,
        notice,
        selectNotice,
    };
}
