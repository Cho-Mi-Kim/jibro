import type { Notice } from './types';
import { recruitmentRegionLabel } from './noticeRegions';

type HousingSource = Pick<Notice, 'region' | 'type'>;

// region already contains the recruitment area confirmed for this notice.
export function noticeHousingFacts(notice: HousingSource) {
    return [
        {
            label: '모집 지역',
            icon: 'pin',
            text: recruitmentRegionLabel(notice.region ?? '') || '확인 필요',
        },
        {
            label: '주택 유형',
            icon: 'building',
            text: notice.type?.trim() || '확인 필요',
        },
    ] as const;
}

export function noticeHousingSummary(notice: HousingSource) {
    return noticeHousingFacts(notice)
        .map((fact) => fact.text)
        .join(' · ');
}
