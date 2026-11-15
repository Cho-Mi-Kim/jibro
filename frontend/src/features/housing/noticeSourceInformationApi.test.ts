import { afterEach, expect, it, vi } from 'vitest';
import { noticeSourceInformation } from './noticeSourceInformationApi';

afterEach(() => vi.unstubAllGlobals());

it('공개 원문 정보는 공고별 읽기 전용 경로에서 그대로 받는다', async () => {
    const body = {
        noticeId: 'notice/id',
        checkedAt: null,
        state: 'PENDING',
        sections: [],
        pendingAreas: ['CONDITION', 'DOCUMENT', 'SCHEDULE'],
    };
    const fetch = vi.fn().mockResolvedValue(Response.json(body));
    vi.stubGlobal('fetch', fetch);
    expect(await noticeSourceInformation('notice/id')).toEqual(body);
    expect(fetch).toHaveBeenCalledExactlyOnceWith(
        '/api/v1/notices/notice%2Fid/source-information',
        expect.objectContaining({ method: 'GET', body: undefined }),
    );
});

it('공개되지 않은 공고 오류는 가짜 발췌나 개인별 안내로 대체하지 않는다', async () => {
    vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(Response.json({}, { status: 404 })),
    );
    await expect(noticeSourceInformation('missing')).rejects.toMatchObject({
        status: 404,
    });
});
