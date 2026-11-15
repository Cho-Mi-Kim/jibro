import { beforeEach, describe, expect, it } from 'vitest';
import { demoRequest as call, resetDemo } from './demo';
import type { Preparation } from './types';
import type { OneTimeCheckResult } from '../preparation/oneTimeCheckApi';

beforeEach(resetDemo);
describe('미저장 확인 시연 계약', () => {
    it('계정 없는 평가가 회원이나 준비 기록을 만들지 않는다', async () => {
        const result = await call<OneTimeCheckResult>(
            '/notices/1/check-once',
            'POST',
            { version: 1, answers: { house: 'NO' } },
        );
        expect(result.documents[1].necessity).toBe('NOT_APPLICABLE');
        expect(result).not.toHaveProperty('id');
        await expect(call('/preparations', 'GET')).rejects.toMatchObject({
            status: 401,
        });
        await call('/auth/login', 'POST', { email: 'demo' });
        expect(await call('/preparations', 'GET')).toEqual([]);
    });
    it('이미 저장한 답변·서류·개인 메모를 미저장 확인에서 읽거나 바꾸지 않는다', async () => {
        await call('/auth/login', 'POST', { email: 'demo' });
        let saved = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        saved = await call<Preparation>(
            '/preparations/' + saved.id + '/answers',
            'PUT',
            { revision: saved.revision, answers: { house: 'YES' } },
        );
        await call<Preparation>(
            '/preparations/' + saved.id + '/documents/1',
            'PATCH',
            { revision: saved.revision, state: 'READY', note: '저장된 메모' },
        );
        const before = await call('/preparations', 'GET');
        const result = await call<OneTimeCheckResult>(
            '/notices/1/check-once',
            'POST',
            { version: 1, answers: { house: 'NO' } },
        );
        expect(result.answers).toEqual({ house: 'NO' });
        expect(result.documents[1].note).toBe('');
        expect(await call('/preparations', 'GET')).toEqual(before);
    });
});
