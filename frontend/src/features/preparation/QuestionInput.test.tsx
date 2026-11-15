import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Answer, Question } from '../housing/types';
import { QuestionInput } from './QuestionInput';

const question: Question = {
    id: 'choices',
    title: '해당 항목',
    reason: '입력 테스트',
    evidence: { label: '테스트', url: null, checkedAt: '2026-11-08' },
    inputKind: 'MULTIPLE',
    constraints: { maxSelections: 2 },
    options: [
        { value: 'A', label: '첫째' },
        { value: 'B', label: '둘째' },
        { value: 'NONE', label: '해당 없음' },
        { value: 'UNKNOWN', label: '모름' },
    ],
};

function mount(initial?: Answer, limit = 2) {
    const changed = vi.fn();
    function Harness() {
        const [value, setValue] = useState(initial);
        return (
            <QuestionInput
                question={{
                    ...question,
                    constraints: { maxSelections: limit },
                }}
                value={value}
                disabled={false}
                onRepeatChoice={() => {}}
                onChange={(next) => {
                    changed(next);
                    setValue(next);
                }}
            />
        );
    }
    render(<Harness />);
    return changed;
}

describe('해당 없음의 단독 선택', () => {
    it('최대 개수 선택 후에도 해당 없음으로 전환하고 일반 항목·모름과 섞이지 않는다', async () => {
        const changed = mount();
        const user = userEvent.setup();
        await user.click(screen.getByRole('checkbox', { name: '첫째' }));
        await user.click(screen.getByRole('checkbox', { name: '둘째' }));
        expect(
            screen.getByRole('checkbox', { name: '해당 없음' }),
        ).toBeEnabled();
        await user.click(screen.getByRole('checkbox', { name: '해당 없음' }));
        expect(changed).toHaveBeenLastCalledWith(['NONE']);
        expect(
            screen.getByRole('checkbox', { name: '첫째' }),
        ).not.toBeChecked();
        expect(
            screen.getByRole('checkbox', { name: '둘째' }),
        ).not.toBeChecked();
        await user.click(screen.getByRole('checkbox', { name: '첫째' }));
        expect(changed).toHaveBeenLastCalledWith(['A']);
        expect(
            screen.getByRole('checkbox', { name: '해당 없음' }),
        ).not.toBeChecked();
        await user.click(screen.getByRole('checkbox', { name: '모름' }));
        expect(changed).toHaveBeenLastCalledWith('UNKNOWN');
        await user.click(screen.getByRole('checkbox', { name: '해당 없음' }));
        expect(changed).toHaveBeenLastCalledWith(['NONE']);
        expect(
            screen.getByRole('checkbox', { name: '모름' }),
        ).not.toBeChecked();
    });

    it('최대 한 개여도 해당 없음에서 다른 항목으로 바꿀 수 있다', async () => {
        const changed = mount(['NONE'], 1);
        expect(screen.getByRole('checkbox', { name: '첫째' })).toBeEnabled();
        await userEvent.click(screen.getByRole('checkbox', { name: '첫째' }));
        expect(changed).toHaveBeenLastCalledWith(['A']);
        expect(screen.getByRole('checkbox', { name: '둘째' })).toBeDisabled();
        expect(
            screen.getByRole('checkbox', { name: '해당 없음' }),
        ).toBeEnabled();
    });

    it('저장된 혼합 응답은 렌더링만으로 변경하지 않고 사용자의 새 선택만 정리한다', async () => {
        const changed = mount(['A', 'NONE']);
        expect(changed).not.toHaveBeenCalled();
        expect(screen.getByRole('checkbox', { name: '첫째' })).toBeChecked();
        expect(
            screen.getByRole('checkbox', { name: '해당 없음' }),
        ).toBeChecked();
        await userEvent.click(screen.getByRole('checkbox', { name: '둘째' }));
        expect(changed).toHaveBeenLastCalledWith(['A', 'B']);
        expect(
            screen.getByRole('checkbox', { name: '해당 없음' }),
        ).not.toBeChecked();
    });
});
