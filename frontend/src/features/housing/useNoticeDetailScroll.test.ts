import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { animateScroll } from '../../shared/ui/animateScroll';
import { useNoticeDetailScroll } from './useNoticeDetailScroll';

vi.mock('../../shared/ui/animateScroll', () => ({
    animateScroll: vi.fn(() => () => {}),
}));
vi.mock('../../shared/ui/motionPreferences', () => ({
    prefersReducedMotion: () => false,
}));

afterEach(() => {
    document.querySelector('[data-scroll-test]')?.remove();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

it('선택할 때 모바일 스크롤 영역 안에서 카드를 정렬하고 화면을 떠날 때 공간을 제거한다', () => {
    const host = document.createElement('div');
    host.dataset.scrollTest = '';
    host.dataset.noticeDetailScroll = '';
    host.style.overflowY = 'auto';
    const card = document.createElement('section');
    host.append(card);
    document.body.append(host);
    vi.spyOn(host, 'getBoundingClientRect').mockReturnValue({
        top: 50,
    } as DOMRect);
    vi.spyOn(card, 'getBoundingClientRect').mockReturnValue({
        top: 400,
    } as DOMRect);
    Object.defineProperty(host, 'clientHeight', { value: 500 });
    Object.defineProperty(host, 'scrollHeight', { value: 700 });
    host.scrollTop = 100;
    let runFrame: FrameRequestCallback = () => {};
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
        runFrame = callback;
        return 1;
    });
    vi.stubGlobal('cancelAnimationFrame', vi.fn());
    const { result, unmount } = renderHook(() => useNoticeDetailScroll());
    result.current.cardRef.current = card;
    act(() => {
        result.current.scrollToCard();
        runFrame(0);
    });
    expect(host.style.getPropertyValue('--summary-scroll-space')).toBe('238px');
    expect(animateScroll).toHaveBeenCalledWith(
        host,
        expect.any(Function),
        false,
    );
    const position = vi.mocked(animateScroll).mock.calls[0][1] as () => number;
    expect(position()).toBe(438);
    unmount();
    expect(host.style.getPropertyValue('--summary-scroll-space')).toBe('');
    expect(animateScroll).toHaveBeenCalledTimes(1);
});
