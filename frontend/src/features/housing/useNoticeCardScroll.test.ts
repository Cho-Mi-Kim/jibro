import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useNoticeCardScroll } from './useNoticeCardScroll';

afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    document.querySelector('[data-scroll-test]')?.remove();
});

it.each([
    [0, false],
    [208, false],
    [208, true],
] as const)(
    '검색 영역 %ipx, 접기 애니메이션 %s에서 스크롤 위치를 유지한다',
    (initialControlsHeight, collapsing) => {
        const host = document.createElement('div');
        host.dataset.scrollTest = '';
        host.innerHTML =
            '<header data-notice-header></header><div data-notice-controls></div><div data-notice-list><article></article></div>';
        document.body.append(host);
        const header = host.querySelector('header')!;
        const controls = host.querySelector('[data-notice-controls]')!;
        if (collapsing) controls.setAttribute('data-collapsed', 'true');
        const list = host.querySelector<HTMLElement>('[data-notice-list]')!;
        const card = host.querySelector('article')!;
        let position = 0;
        let contentHeight = 1000;
        let controlsHeight: number = initialControlsHeight;
        let resize = () => {};
        const frames: FrameRequestCallback[] = [];
        vi.stubGlobal('innerHeight', 852);
        vi.spyOn(window, 'scrollY', 'get').mockImplementation(() => position);
        vi.spyOn(
            document.documentElement,
            'scrollHeight',
            'get',
        ).mockImplementation(
            () =>
                contentHeight +
                (collapsing ? controlsHeight : 0) +
                (parseFloat(
                    list.style.getPropertyValue('--notice-scroll-space'),
                ) || 0),
        );
        vi.spyOn(header, 'getBoundingClientRect').mockReturnValue({
            height: 66,
        } as DOMRect);
        vi.spyOn(controls, 'getBoundingClientRect').mockImplementation(
            () => ({ height: controlsHeight }) as DOMRect,
        );
        vi.spyOn(card, 'getBoundingClientRect').mockImplementation(
            () =>
                ({
                    top: 600 + (collapsing ? controlsHeight : 0) - position,
                }) as DOMRect,
        );
        card.scrollIntoView = vi.fn(() => {
            position = 522 - (collapsing ? 0 : controlsHeight);
        });
        vi.stubGlobal(
            'requestAnimationFrame',
            (callback: FrameRequestCallback) => frames.push(callback),
        );
        vi.stubGlobal('cancelAnimationFrame', vi.fn());
        vi.stubGlobal(
            'ResizeObserver',
            class {
                constructor(callback: () => void) {
                    resize = callback;
                }
                observe() {}
                disconnect() {}
            },
        );
        const ref = { current: card };
        const hook = renderHook(
            ({ expanded }) => useNoticeCardScroll(ref, expanded),
            { initialProps: { expanded: true } },
        );
        act(() => frames.shift()!(0));
        expect(card.style.scrollMarginTop).toBe(`${78 + controlsHeight}px`);
        expect(list.style.getPropertyValue('--notice-scroll-space')).toBe(
            `${374 - (collapsing ? 0 : controlsHeight)}px`,
        );
        expect(card.scrollIntoView).toHaveBeenCalledTimes(1);
        expect(card.getBoundingClientRect().top).toBe(78 + controlsHeight);

        if (collapsing) {
            // The toolbar can shrink without reducing the maximum scroll below
            // the current position, and without another programmatic scroll.
            controlsHeight = 0;
            expect(
                document.documentElement.scrollHeight - window.innerHeight,
            ).toBeGreaterThanOrEqual(position);
            act(() => {
                resize();
                frames.shift()!(0);
            });
            expect(card.getBoundingClientRect().top).toBe(78);
            expect(card.scrollIntoView).toHaveBeenCalledTimes(1);
        }

        // Preview loads, then the user scrolls: resize must not force them back.
        contentHeight = 1450;
        position = 100;
        act(() => {
            resize();
            frames.shift()!(0);
        });
        expect(list.style.getPropertyValue('--notice-scroll-space')).toBe(
            '0px',
        );
        expect(card.scrollIntoView).toHaveBeenCalledTimes(1);
        hook.rerender({ expanded: false });
        expect(list.style.getPropertyValue('--notice-scroll-space')).toBe('');
    },
);
