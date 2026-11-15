import { describe, expect, it } from 'vitest';
import { titleTextDiff } from './titleTextDiff';

describe('제목 전환 구간', () => {
    it.each([
        ['청년 전세임대 1순위', '2026년 청년 전세임대 1순위 입주자 수시모집'],
        ['[정정] 평택 공공임대[리츠]', '[정정공고] 평택 공공임대주택[리츠]'],
        ['행복주택', '행복주택'],
    ])('두 제목을 각각 정확하게 복원한다', (summary, original) => {
        const parts = titleTextDiff(summary, original);
        expect(
            parts
                .filter((part) => part.kind !== 'original')
                .map((part) => part.text)
                .join(''),
        ).toBe(summary);
        expect(
            parts
                .filter((part) => part.kind !== 'summary')
                .map((part) => part.text)
                .join(''),
        ).toBe(original);
    });
});
