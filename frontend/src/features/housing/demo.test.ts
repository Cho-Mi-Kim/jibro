import { beforeEach, describe, expect, it } from 'vitest';
import { demoRequest as call, resetDemo } from './demo';
import type { Notice, Page, Preparation, Review } from './types';
beforeEach(resetDemo);
describe('계약 시연 어댑터', () => {
    it('비회원 저장은 차단하고 같은 공고 시작은 중복 생성하지 않는다', async () => {
        await expect(
            call('/preparations', 'POST', { noticeId: '1', version: 1 }),
        ).rejects.toMatchObject({ status: 401 });
        await call('/auth/login', 'POST', { email: 'demo' });
        const p = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        const second = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        expect(second.id).toBe(p.id);
    });
    it('모름을 완료로 처리하지 않고 오래된 revision 저장을 차단한다', async () => {
        await call('/auth/login', 'POST', { email: 'demo' });
        const p = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        const next = await call<Preparation>(
            '/preparations/' + p.id + '/answers',
            'PUT',
            {
                revision: 1,
                answers: { residence: 'UNKNOWN', house: 'UNKNOWN' },
            },
        );
        expect(next.progress).toBe(0);
        expect(next.warnings).toHaveLength(2);
        await expect(
            call('/preparations/' + p.id + '/answers', 'PUT', {
                revision: 1,
                answers: {},
            }),
        ).rejects.toMatchObject({ status: 409 });
    });
    it('기본 정보 검수 실패는 목록·상세·준비 시작 모두 차단한다', async () => {
        await call('/auth/login', 'POST', { email: 'admin@demo.local' });
        await call<Review>('/admin/reviews/1', 'PATCH', {
            basic: 'HOLD',
            guide: 'PASS',
            reason: '출처 재확인',
            revision: 1,
        });
        const page = await call<Page<Notice>>('/notices', 'GET');
        expect(page.items.some((n) => n.id === '1')).toBe(false);
        await expect(call('/notices/1', 'GET')).rejects.toMatchObject({
            status: 404,
        });
        await expect(
            call('/preparations', 'POST', { noticeId: '1', version: 1 }),
        ).rejects.toMatchObject({ status: 409 });
    });
});
