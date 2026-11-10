import { act, fireEvent, renderHook } from '@testing-library/react';
import { expect, it } from 'vitest';
import { useNoticeToolbarReveal } from './useNoticeToolbarReveal';

it('자동 이동은 무시하고 위 스크롤은 펼치고 아래 스크롤은 다시 접는다', () => {
    const expansion = { id: 'first' };
    const hook = renderHook(() => useNoticeToolbarReveal(expansion));
    expect(hook.result.current).toBe(true);
    fireEvent.scroll(window);
    fireEvent.wheel(window, { deltaY: 80 });
    fireEvent.wheel(window, { deltaY: -2, deltaX: 50 });
    fireEvent.wheel(window, { deltaY: -80, ctrlKey: true });
    expect(hook.result.current).toBe(true);
    fireEvent.wheel(window, { deltaY: -80 });
    expect(hook.result.current).toBe(false);
    fireEvent.scroll(window);
    expect(hook.result.current).toBe(false);
    fireEvent.wheel(window, { deltaY: 80 });
    expect(hook.result.current).toBe(true);
    fireEvent.wheel(window, { deltaY: -80 });
    expect(hook.result.current).toBe(false);
});

it('다른 공고를 펼치면 다시 접고 전체 닫기·조건 변경으로 펼침이 없어지면 복원한다', () => {
    const first = { id: 'first' };
    const second = { id: 'second' };
    const hook = renderHook(
        ({ expansion }: { expansion: object | null }) =>
            useNoticeToolbarReveal(expansion),
        { initialProps: { expansion: first as object | null } },
    );
    fireEvent.keyDown(window, { key: 'PageUp' });
    expect(hook.result.current).toBe(false);
    hook.rerender({ expansion: second });
    expect(hook.result.current).toBe(true);
    hook.rerender({ expansion: null });
    expect(hook.result.current).toBe(false);
    hook.rerender({ expansion: { id: 'first' } });
    expect(hook.result.current).toBe(true);
});

it('손가락을 아래로 밀어 위로 스크롤하면 복원하며 작은 흔들림·가로 스와이프는 무시한다', () => {
    const expansion = { id: 'first' };
    const hook = renderHook(() => useNoticeToolbarReveal(expansion));
    fireEvent.touchStart(window, { touches: [{ clientX: 50, clientY: 100 }] });
    fireEvent.touchMove(window, { touches: [{ clientX: 50, clientY: 95 }] });
    fireEvent.touchMove(window, { touches: [{ clientX: 50, clientY: 105 }] });
    fireEvent.touchMove(window, { touches: [{ clientX: 100, clientY: 110 }] });
    expect(hook.result.current).toBe(true);
    fireEvent.touchMove(window, { touches: [{ clientX: 50, clientY: 120 }] });
    expect(hook.result.current).toBe(false);
    // Reversing the same gesture must collapse again.
    fireEvent.touchMove(window, { touches: [{ clientX: 50, clientY: 100 }] });
    expect(hook.result.current).toBe(true);
    fireEvent.touchMove(window, { touches: [{ clientX: 50, clientY: 120 }] });
    expect(hook.result.current).toBe(false);
});

it('입력 중 화살표는 무시하고 키보드 역방향 탐색에서는 검색 영역을 복원한다', () => {
    const expansion = { id: 'first' };
    const hook = renderHook(() => useNoticeToolbarReveal(expansion));
    const input = document.createElement('input');
    document.body.append(input);
    fireEvent.keyDown(input, { key: 'ArrowUp' });
    expect(hook.result.current).toBe(true);
    fireEvent.keyDown(window, { key: 'Tab', shiftKey: true });
    expect(hook.result.current).toBe(false);
    fireEvent.wheel(input, { deltaY: 80 });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(hook.result.current).toBe(false);
    fireEvent.keyDown(window, { key: 'PageDown' });
    expect(hook.result.current).toBe(true);
    input.remove();
    act(() => hook.unmount());
});

it('열린 필터에서 스크롤해도 검색 영역을 접지 않는다', () => {
    const expansion = { id: 'first' };
    const hook = renderHook(() => useNoticeToolbarReveal(expansion));
    const dialog = document.createElement('dialog');
    document.body.append(dialog);
    fireEvent.keyDown(window, { key: 'PageUp' });
    fireEvent.wheel(dialog, { deltaY: 80 });
    fireEvent.touchStart(dialog, { touches: [{ clientX: 50, clientY: 100 }] });
    fireEvent.touchMove(dialog, { touches: [{ clientX: 50, clientY: 50 }] });
    expect(hook.result.current).toBe(false);
    dialog.remove();
});
