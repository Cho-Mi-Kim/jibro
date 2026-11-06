import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ArchivePreparationSelect } from './ArchivePreparationSelect';

const items = [
    { id: 'first', title: '청년 매입임대 입주자 모집' },
    { id: 'selected', title: '서울 청년 전세임대 입주자 모집' },
    { id: 'last', title: '경기 행복주택 입주자 모집' },
];

const scrollIntoView = Object.getOwnPropertyDescriptor(
    HTMLElement.prototype,
    'scrollIntoView',
);
beforeEach(() => {
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
        configurable: true,
        writable: true,
        value: vi.fn(),
    });
});
afterEach(() => {
    if (scrollIntoView)
        Object.defineProperty(
            HTMLElement.prototype,
            'scrollIntoView',
            scrollIntoView,
        );
    else Reflect.deleteProperty(HTMLElement.prototype, 'scrollIntoView');
});

function mount() {
    const onChange = vi.fn();
    render(
        <>
            <ArchivePreparationSelect
                items={items}
                value="selected"
                onChange={onChange}
            />
            <button type="button">선택 영역 밖</button>
        </>,
    );
    return {
        onChange,
        trigger: screen.getByRole('combobox', { name: '현재 준비 공고' }),
    };
}

describe('준비 보관함 공고 선택', () => {
    it('현재 공고와 선택 상태를 알리고 열기만 하면 변경하지 않는다', async () => {
        const user = userEvent.setup();
        const { onChange, trigger } = mount();

        expect(trigger).toHaveTextContent(items[1].title);
        expect(trigger).toHaveAttribute('aria-expanded', 'false');
        expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
        expect(onChange).not.toHaveBeenCalled();

        await user.click(trigger);

        expect(trigger).toHaveAttribute('aria-expanded', 'true');
        const list = within(screen.getByRole('listbox'));
        expect(list.getAllByRole('option')).toHaveLength(items.length);
        for (const item of items) {
            expect(
                list.getByRole('option', { name: item.title }),
            ).toHaveAttribute('aria-selected', String(item.id === 'selected'));
        }
        expect(onChange).not.toHaveBeenCalled();
    });

    it('공고를 누르면 해당 준비 ID를 한 번 전달하고 목록을 닫는다', async () => {
        const user = userEvent.setup();
        const { onChange, trigger } = mount();

        await user.click(trigger);
        await user.click(screen.getByRole('option', { name: items[0].title }));

        expect(onChange).toHaveBeenCalledExactlyOnceWith(items[0].id);
        expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
        expect(trigger).toHaveAttribute('aria-expanded', 'false');
    });

    it('키보드로 현재 선택에서 탐색한 뒤 Enter로 공고를 선택한다', async () => {
        const user = userEvent.setup();
        const { onChange, trigger } = mount();

        await user.tab();
        expect(trigger).toHaveFocus();
        await user.keyboard('{ArrowDown}');
        const first = screen.getByRole('option', { name: items[0].title });
        const selected = screen.getByRole('option', { name: items[1].title });
        const last = screen.getByRole('option', { name: items[2].title });
        expect(selected).toHaveFocus();

        await user.keyboard('{ArrowDown}');
        expect(last).toHaveFocus();
        await user.keyboard('{ArrowUp}');
        expect(selected).toHaveFocus();
        await user.keyboard('{Home}');
        expect(first).toHaveFocus();
        await user.keyboard('{End}');
        expect(last).toHaveFocus();
        expect(selected).toHaveAttribute('aria-selected', 'true');
        expect(onChange).not.toHaveBeenCalled();

        await user.keyboard('{Enter}');

        expect(onChange).toHaveBeenCalledExactlyOnceWith(items[2].id);
        expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    });

    it('Escape와 바깥 포인터 입력은 공고를 바꾸지 않고 목록만 닫는다', async () => {
        const user = userEvent.setup();
        const { onChange, trigger } = mount();

        await user.click(trigger);
        await user.keyboard('{End}{Escape}');

        expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
        expect(trigger).toHaveFocus();
        expect(trigger).toHaveAttribute('aria-expanded', 'false');
        expect(onChange).not.toHaveBeenCalled();

        await user.click(trigger);
        await user.pointer({
            target: screen.getByRole('button', { name: '선택 영역 밖' }),
            keys: '[MouseLeft]',
        });

        expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
        expect(trigger).toHaveAttribute('aria-expanded', 'false');
        expect(onChange).not.toHaveBeenCalled();
    });
});
