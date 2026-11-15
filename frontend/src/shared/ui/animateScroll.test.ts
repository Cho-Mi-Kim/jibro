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
    animateScroll(panel, 100);
    frames.shift()!(0);
    frames.shift()!(375);
    expect(panel.scrollTo).toHaveBeenLastCalledWith({
        top: 50,
        behavior: 'instant',
    });
    frames.shift()!(750);
    expect(panel.scrollTo).toHaveBeenLastCalledWith({
        top: 100,
        behavior: 'instant',
    });
    expect(frames).toHaveLength(0);
});
