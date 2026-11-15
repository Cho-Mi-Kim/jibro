import { beforeEach, describe, expect, it } from 'vitest';
import { demoRequest as call, resetDemo } from './demo';
import type { Notice, Page, Preparation, Review } from './types';
import type { Experience } from '../member/memberApi';
beforeEach(resetDemo);
describe('계약 시연 어댑터', () => {
    it('같은 변경 번호의 두 번째 보관은 첫 메모를 덮어쓰지 않는다', async () => {
        await call('/auth/login', 'POST', { email: 'demo' });
        const p = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        const path = '/member/archive/' + p.id;
        await call(path, 'PUT', { revision: p.revision, note: '첫 메모' });
        for (const revision of [p.revision, p.revision + 1]) {
            await expect(
                call(path, 'PUT', { revision, note: '덮어쓰기' }),
            ).rejects.toMatchObject({ status: 409 });
        }
        const archived = await call<Experience[]>('/member/archive', 'GET');
        expect(archived[0].note).toBe('첫 메모');
    });
    it('보관과 복원 후 오래된 저장은 거절하고 최신 기록은 수정할 수 있다', async () => {
        await call('/auth/login', 'POST', { email: 'demo' });
        const p = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        const path = '/member/archive/' + p.id;
        await call(path, 'PUT', { revision: p.revision, note: '메모' });
        await call(path, 'DELETE');
        await call(path, 'DELETE');
        const restored = await call<Preparation>(
            '/preparations/' + p.id,
            'GET',
        );
        expect(restored.revision).toBe(p.revision + 2);
        await expect(
            call(path, 'PUT', { revision: p.revision, note: '오래된 요청' }),
        ).rejects.toMatchObject({ status: 409 });
        const answersPath = '/preparations/' + p.id + '/answers';
        await expect(
            call(answersPath, 'PUT', { revision: p.revision, answers: {} }),
        ).rejects.toMatchObject({ status: 409 });
        const updated = await call<Preparation>(answersPath, 'PUT', {
            revision: restored.revision,
            answers: { residence: 'YES' },
        });
        expect(updated.revision).toBe(restored.revision + 1);
        expect(updated.answers).toEqual({ residence: 'YES' });
    });
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
    it('완료 서류의 해당 여부가 바뀌면 메모는 보존하고 재확인을 우선한다', async () => {
        await call('/auth/login', 'POST', { email: 'demo' });
        let p = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        const path = '/preparations/' + p.id;
        p = await call<Preparation>(path + '/answers', 'PUT', {
            revision: p.revision,
            answers: { house: 'YES' },
        });
        const conditional = p.documents.find(
            (d) => d.necessity === 'CONDITIONAL',
        )!;
        p = await call<Preparation>(
            path + '/documents/' + conditional.id,
            'PUT',
            {
                revision: p.revision,
                state: 'READY',
                note: '보존할 메모',
            },
        );
        p = await call<Preparation>(path + '/answers', 'PUT', {
            revision: p.revision,
            answers: { house: 'UNKNOWN' },
        });
        expect(p.documents.find((d) => d.id === conditional.id)).toMatchObject({
            necessity: 'UNKNOWN',
            state: 'RECHECK',
            note: '보존할 메모',
        });
        expect(p.nextAction).toMatchObject({
            label: '서류 다시 확인하기',
            path: path + '/documents',
        });
        p = await call<Preparation>(path + '/answers', 'PUT', {
            revision: p.revision,
            answers: { house: 'YES' },
        });
        expect(p.documents.find((d) => d.id === conditional.id)).toMatchObject({
            necessity: 'CONDITIONAL',
            state: 'UNCHECKED',
            note: '보존할 메모',
        });
    });
    it('없는 질문과 허용되지 않은 답변은 기록과 revision을 바꾸지 않는다', async () => {
        await call('/auth/login', 'POST', { email: 'demo' });
        const p = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        for (const answers of [{ missing: 'YES' }, { house: 'INVALID' }]) {
            await expect(
                call('/preparations/' + p.id + '/answers', 'PUT', {
                    revision: p.revision,
                    answers,
                }),
            ).rejects.toMatchObject({ status: 400 });
        }
        expect(await call('/preparations/' + p.id, 'GET')).toEqual(p);
    });
    it('검수 상태를 다시 읽어 저장을 막고 기존 준비 기록은 보존한다', async () => {
        await call('/auth/login', 'POST', { email: 'admin@demo.local' });
        const p = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        await call('/admin/reviews/1', 'PATCH', {
            revision: 1,
            basic: 'PASS',
            guide: 'HOLD',
            reason: '안내 재검수',
        });
        const latest = await call<Preparation>('/preparations/' + p.id, 'GET');
        expect(latest.guideState).toBe('REVIEWING');
        expect(latest.nextAction.path).toBe('/notices/1');
        await expect(
            call('/preparations/' + p.id + '/answers', 'PUT', {
                revision: p.revision,
                answers: { house: 'YES' },
            }),
        ).rejects.toMatchObject({ status: 409 });
        const existing = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        expect(existing.id).toBe(p.id);
        expect(existing.answers).toEqual({});
        expect(existing.revision).toBe(p.revision);
    });
    it('다른 사용자로 전환하면 앞 사용자의 시연 기록이 보이지 않는다', async () => {
        await call('/auth/login', 'POST', { email: 'demo' });
        const p = await call<Preparation>('/preparations', 'POST', {
            noticeId: '1',
            version: 1,
        });
        await call('/auth/login', 'POST', { email: 'admin@demo.local' });
        expect(await call('/preparations', 'GET')).toEqual([]);
        await expect(
            call('/preparations/' + p.id, 'GET'),
        ).rejects.toMatchObject({ status: 404 });
    });
});
