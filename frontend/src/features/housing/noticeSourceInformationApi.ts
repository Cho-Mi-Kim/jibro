import { call } from '../../shared/api/transport';
import type { Evidence } from './types';

export type SourceInformationArea =
    'CONDITION' | 'DOCUMENT' | 'SCHEDULE' | 'REFERENCE';

export interface NoticeSourceInformationData {
    noticeId: string;
    checkedAt: string | null;
    state: 'AVAILABLE' | 'PENDING';
    sections: {
        id: string;
        area: SourceInformationArea;
        title: string;
        text: string;
        evidence: Evidence;
        form?: Evidence;
    }[];
    confirmedItems?: NoticeSourceInformationData['sections'];
    pendingAreas: SourceInformationArea[];
}

export function noticeSourceInformation(noticeId: string) {
    return call<NoticeSourceInformationData>(
        '/notices/' + encodeURIComponent(noticeId) + '/source-information',
    );
}
