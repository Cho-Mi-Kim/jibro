import { describe, expect, it } from 'vitest';
import { supportSummaryRows } from './supportSummaryRows';

describe('지원 요약의 읽기 단위', () => {
    it('날짜·소수점·금액·예외를 그대로 보존한다', () => {
        const sentences = [
            '2026.10.28. 현재 공고 요건을 충족하는 가구가 대상입니다.',
            '전용 36.5㎡ 주택을 시세의 30~40%로 공급합니다.',
            '소득·총자산 기준은 제외하지만 자동차 기준은 유지합니다.',
        ];
        const rows = supportSummaryRows(sentences.join(' '));
        expect(rows.map((row) => row.text)).toEqual(sentences);
        expect(rows.map((row) => row.label)).toEqual([
            '지원 대상',
            '지원 내용',
            '함께 확인',
        ]);
    });
    it('명시된 대상과 내용 표제를 분리하고 뒤따르는 세부 설명을 보존한다', () => {
        const support =
            '예비입주자를 모집합니다. 자동차 기준은 별도로 적용됩니다.';
        const summary =
            '지원 대상: 공고의 청년 요건에 해당하는 사람입니다.\n지원 내용: ' +
            support;
        expect(supportSummaryRows(summary)).toEqual([
            {
                label: '지원 대상',
                text: '공고의 청년 요건에 해당하는 사람입니다.',
            },
            { label: '지원 내용', text: support },
        ]);
        expect(
            supportSummaryRows(
                summary + ' 함께 확인: 전용 36.5㎡ 기준입니다.',
            ).at(-1),
        ).toEqual({ label: '함께 확인', text: '전용 36.5㎡ 기준입니다.' });
    });
    it('대상부터 시작하지 않는 기존 원고에 의미를 임의로 붙이지 않는다', () => {
        const summary = 'LH의 공식 모집 안내입니다. 공고 원문을 확인하세요.';
        const rows = supportSummaryRows(summary);
        expect(rows.map((row) => row.text).join(' ')).toBe(summary);
        expect(rows.every((row) => row.label === null)).toBe(true);
    });
    it('긴 원고와 빈 원고에서도 내용을 버리거나 가짜 항목을 만들지 않는다', () => {
        const summary =
            '청년이 대상입니다. 임대주택을 공급합니다. 소득 기준이 있습니다. 예외도 확인하세요.';
        expect(
            supportSummaryRows(summary)
                .map((row) => row.text)
                .join(' '),
        ).toBe(summary);
        expect(
            supportSummaryRows(summary).every((row) => row.label === null),
        ).toBe(true);
        expect(supportSummaryRows('')).toEqual([]);
    });
});
