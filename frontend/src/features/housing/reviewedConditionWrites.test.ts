import { expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ call: vi.fn(), load: vi.fn() }));
vi.mock('../../shared/api/transport', () => ({
    call: mocks.call,
    clearToken: vi.fn(),
    setToken: vi.fn(),
}));
vi.mock('./reviewedConditionPresentation', () => ({
    withReviewedConditions: mocks.load,
}));
import { api } from './api';

it('a successful write is not reported as a save failure when display chunks are unavailable', async () => {
    const saved = { id: 'preparation', revision: 3, questions: [] };
    mocks.call.mockResolvedValue(saved);
    mocks.load.mockRejectedValue(new Error('chunk unavailable'));
    await expect(api.start('notice', 1)).resolves.toBe(saved);
    await expect(api.refreshPreparation('preparation', 2, 3)).resolves.toBe(
        saved,
    );
    await expect(api.answers('preparation', 2, {})).resolves.toBe(saved);
    await expect(api.notice('notice')).rejects.toThrow('chunk unavailable');
});
