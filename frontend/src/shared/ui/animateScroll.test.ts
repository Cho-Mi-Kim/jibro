import { afterEach, expect, it, vi } from 'vitest';
import { animateScroll } from './animateScroll';

afterEach(() => {
    document.documentElement.style.removeProperty('--motion-duration');
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});

it('스크롤을 750ms에 걸쳐 완료한다', () => {
    document.documentElement.style.setProperty('--motion-duration', '750ms');
    const panel = document.createElement('div');
    panel.scrollTo = vi.fn();
    const frames: FrameRequestCallback[] = [];
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
        frames.push(callback);
        return frames.length;
    });
    vi.stubGlobal('cancelAnimationFrame', vi.fn());
    const finish = vi.fn();
    const cancel = animateScroll(panel, 100, false, finish);
    frames.shift()!(0);
    frames.shift()!(375);
    expect(panel.scrollTo).toHaveBeenLastCalledWith({
        top: 50,
        behavior: 'instant',
    });
    expect(finish).not.toHaveBeenCalled();
    frames.shift()!(750);
    expect(panel.scrollTo).toHaveBeenLastCalledWith({
        top: 100,
        behavior: 'instant',
    });
    expect(frames).toHaveLength(0);
    expect(finish).toHaveBeenCalledTimes(1);
    cancel();
    expect(finish).toHaveBeenCalledTimes(1);
});

it('동작 줄이기에서는 즉시 이동하고, 수동 스크롤로 중단해도 정리를 한 번만 수행한다', () => {
    const panel = document.createElement('div');
    panel.scrollTo = vi.fn();
    const finish = vi.fn();
    animateScroll(panel, 100, true, finish);
    expect(panel.scrollTo).toHaveBeenCalledWith({
        top: 100,
        behavior: 'instant',
    });
    expect(finish).toHaveBeenCalledTimes(1);
    finish.mockClear();
    const cancel = animateScroll(panel, 200, false, finish);
    panel.dispatchEvent(new Event('wheel'));
    cancel();
    expect(finish).toHaveBeenCalledTimes(1);
});
