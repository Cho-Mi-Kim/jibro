import { beforeEach, expect, it, vi } from 'vitest';
import type { Question } from './types';
const state = vi.hoisted(() => ({ fail: true }));
vi.mock(
    './reviewedConditions/notice-0000e667-e82e-4db9-b3cf-947ba42146f8.json',
    () => ({
        get default() {
            if (state.fail) throw new Error('chunk unavailable');
            return { entries: [] };
        },
    }),
);
import catalog from './reviewedConditions/index.json';
import { withReviewedConditions } from './reviewedConditionPresentation';

beforeEach(() => {
    state.fail = true;
});
it('a failed display chunk is visible and does not poison a later retry', async () => {
    const url = Object.entries(catalog).find(([, ids]) =>
        ids.includes('0000e667-e82e-4db9-b3cf-947ba42146f8'),
    )![0];
    const data = {
        questions: [
            {
                id: 'a',
                title: '질문',
                reason: '원문',
                evidence: { url, label: '원문', checkedAt: '' },
            } satisfies Question,
        ],
    };
    await expect(withReviewedConditions(data)).rejects.toThrow(
        '검수한 설명을 불러오지 못했어요',
    );
    state.fail = false;
    await expect(withReviewedConditions(data)).resolves.toBe(data);
});
