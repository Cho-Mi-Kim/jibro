import { useQuery } from '@tanstack/react-query';
import {
    noticeSourceInformation,
    type NoticeSourceInformationData,
} from './noticeSourceInformationApi';
import type { Notice } from './types';

export interface NoticeSourceInformationState {
    data?: NoticeSourceInformationData;
    loading: boolean;
    failed: boolean;
    retry: () => void;
}

/** Raw source reading is separate from published questions and personal preparation. */
export function useNoticeSourceInformation(
    notice?: Notice,
): NoticeSourceInformationState {
    const noticeId = notice?.id ?? '';
    const enabled =
        notice?.guideState === 'REVIEWING' ||
        notice?.guideState === 'CHANGE_REVIEW';
    const query = useQuery({
        queryKey: [
            'notice-source-information',
            noticeId,
            notice?.version,
            notice?.guideState,
            notice?.basicCheckedAt,
            notice?.collectedAt,
        ],
        queryFn: async () => {
            const data = await noticeSourceInformation(noticeId);
            if (data.noticeId !== noticeId)
                throw new Error('공고의 원문 확인 정보를 다시 불러와 주세요.');
            return data;
        },
        enabled,
        staleTime: 0,
        gcTime: 0,
        refetchOnMount: 'always',
    });
    return {
        // Never show an older excerpt while checking the current source again.
        data:
            enabled && !query.isFetching && !query.isError
                ? query.data
                : undefined,
        loading: enabled && query.isFetching,
        failed: enabled && query.isError,
        retry: () => void query.refetch(),
    };
}
