import { useSession } from '../auth/useSession';
import { usePreparationList } from '../preparation/usePreparationList';
import { usePreparationRecord } from '../preparation/usePreparationRecord';
import type { NoticeComparison } from './comparisonApi';

export function useComparisonPreparations(
    comparisons: [NoticeComparison | undefined, NoticeComparison | undefined],
) {
    const session = useSession();
    const list = usePreparationList(comparisons.some(Boolean));
    const rows = comparisons.map((data) =>
        session.user && !session.error && list.isSuccess
            ? list.data.find((row) => row.noticeId === data?.notice.id)
            : undefined,
    );
    const eligible = rows.map((row, index) =>
        row &&
        row.version === comparisons[index]?.notice.version &&
        comparisons[index]?.notice.guideState === 'AVAILABLE'
            ? row
            : undefined,
    );
    const first = usePreparationRecord(eligible[0]?.id, eligible[0]?.revision);
    const second = usePreparationRecord(eligible[1]?.id, eligible[1]?.revision);
    return [first, second].map((record, index) => {
        const notice = comparisons[index]?.notice;
        const row = rows[index];
        const preparation = record.isSuccess ? record.data : undefined;
        const retry = () => {
            void list.refetch();
            if (eligible[index]) void record.refetch();
        };
        let status: 'public' | 'loading' | 'error' | 'outdated' | 'personal' =
            'public';
        if (!notice || notice.guideState !== 'AVAILABLE') return { status };
        if (session.error)
            return { status: 'error' as const, retry: session.refresh };
        if (session.loading) return { status: 'loading' as const };
        if (!session.user) return { status };
        if (list.isError) status = 'error';
        else if (list.isPending) status = 'loading';
        else if (!row) status = 'public';
        else if (row.version !== notice.version) status = 'outdated';
        else if (record.isError) status = 'error';
        else if (record.isPending) status = 'loading';
        else if (
            !preparation ||
            preparation.id !== row.id ||
            preparation.noticeId !== notice.id ||
            preparation.version !== notice.version ||
            preparation.guideState !== 'AVAILABLE'
        )
            status = 'outdated';
        else if (preparation.revision < row.revision)
            status = record.isFetching ? 'loading' : 'error';
        else if (
            preparation.questions.some(
                (question) => preparation.answers[question.id] !== undefined,
            )
        )
            status = 'personal';
        return {
            status,
            preparation: status === 'personal' ? preparation : undefined,
            retry,
        };
    });
}
