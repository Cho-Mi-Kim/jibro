import catalog from '../../../../data/guides/guide-catalog.json';
import { ApiError } from '../../shared/api/client';
import type { Guide, GuidePageData } from '../housing/types';

const guides: Guide[] = catalog.items;
const normalize = (value: string) => value.normalize('NFC').toLowerCase();

export function demoGuides(params: URLSearchParams): GuidePageData {
    const category = params.get('category') ?? '';
    const keyword = params.get('keyword') ?? '';
    const page = Number(params.get('page') ?? '0');
    if (
        !Number.isInteger(page) ||
        page < 0 ||
        page > 10000 ||
        keyword.length > 200 ||
        (category && !guides.some((guide) => guide.category === category))
    ) {
        throw new ApiError(400, '가이드 검색 조건을 확인해 주세요.');
    }
    const terms = normalize(keyword).trim().split(/\s+/);
    const matching = guides.filter(
        (guide) =>
            (!category || guide.category === category) &&
            terms.every((term) =>
                normalize(
                    [
                        guide.category,
                        guide.title,
                        guide.body,
                        guide.scope,
                        guide.evidence.label,
                        ...(guide.additionalEvidence ?? []).map(
                            (source) => source.label,
                        ),
                    ].join(' '),
                ).includes(term),
            ),
    );
    return {
        items: matching.slice(page * 20, (page + 1) * 20),
        page,
        hasMore: matching.length > (page + 1) * 20,
        totalPages: Math.ceil(matching.length / 20),
    };
}
