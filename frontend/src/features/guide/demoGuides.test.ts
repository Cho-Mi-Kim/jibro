import { describe, expect, it } from 'vitest';
import catalog from '../../../../data/guides/guide-catalog.json';
import { demoGuides } from './demoGuides';

describe('공식 자료로 검토한 공통 가이드', () => {
    it('공고의 가짜 질문 대신 서버와 같은 원고 전부를 중복 없이 페이지로 제공한다', () => {
        const all = [];
        for (let page = 0; ; page++) {
            const result = demoGuides(
                new URLSearchParams({ page: String(page) }),
            );
            expect(result.items.length).toBeLessThanOrEqual(20);
            expect(result.totalPages).toBe(
                Math.ceil(catalog.items.length / 20),
            );
            all.push(...result.items);
            if (!result.hasMore) break;
        }
        expect(all).toEqual(catalog.items);
        expect(new Set(all.map((guide) => guide.id)).size).toBe(all.length);
        expect(new Set(all.map((guide) => guide.category)).size).toBe(5);
        for (const guide of all) {
            expect(guide.scope).toBeTruthy();
            expect(guide.evidence.url).toMatch(/^https:\/\//);
            expect(guide.evidence.checkedAt).toBe(catalog.reviewedAt);
            expect(guide.freshness).toBeUndefined();
        }
    });

    it('주제 안에서 공백으로 구분한 단어를 함께 검색하고 범위·추가 출처도 검색한다', () => {
        for (const guide of catalog.items) {
            const result = demoGuides(
                new URLSearchParams({
                    category: guide.category,
                    keyword: guide.title,
                }),
            );
            expect(result.items.map((item) => item.id)).toContain(guide.id);
            expect(result.totalPages).toBe(1);
        }
        const guide = catalog.items.find(
            (item) => item.additionalEvidence.length > 0,
        )!;
        const result = demoGuides(
            new URLSearchParams({ keyword: guide.additionalEvidence[0].label }),
        );
        expect(result.items.some((item) => item.id === guide.id)).toBe(true);
        expect(
            demoGuides(new URLSearchParams({ keyword: '없는검색어98765' }))
                .items,
        ).toEqual([]);
        expect(
            demoGuides(new URLSearchParams({ keyword: '없는검색어98765' }))
                .totalPages,
        ).toBe(0);
    });

    it('잘못된 주제·페이지·너무 긴 검색은 서버처럼 거절한다', () => {
        const invalidParams: Record<string, string>[] = [
            { category: '없는주제' },
            { page: '-1' },
            { page: '0.5' },
            { page: 'NaN' },
            { page: '10001' },
            { keyword: '가'.repeat(201) },
        ];
        for (const params of invalidParams) {
            expect(() => demoGuides(new URLSearchParams(params))).toThrow();
        }
    });
});
