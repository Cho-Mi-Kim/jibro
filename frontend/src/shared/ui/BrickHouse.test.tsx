import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, expect, it } from 'vitest';
import { BrickHouse } from './BrickHouse';

afterEach(cleanup);

it('서버 준비도가 늘거나 줄면 쌓인 벽돌도 바뀌고 100%에서 모두 완성된다', () => {
    const { container, rerender } = render(
        <BrickHouse progress={0} stage={1} />,
    );
    const count = () =>
        container.querySelectorAll('[data-built="true"]').length;
    expect(count()).toBe(0);
    rerender(<BrickHouse progress={35} stage={2} />);
    const low = count();
    expect(low).toBeGreaterThan(0);
    rerender(<BrickHouse progress={65} stage={3} />);
    expect(count()).toBeGreaterThan(low);
    expect(screen.getByRole('img')).toHaveAccessibleName(
        '준비도 65% · 벽돌이 쌓이는 집',
    );
    rerender(<BrickHouse progress={35} stage={2} />);
    expect(count()).toBe(low);
    rerender(<BrickHouse progress={99} stage={4} />);
    expect(
        container.querySelectorAll('[data-built="false"]').length,
    ).toBeGreaterThan(0);
    rerender(<BrickHouse progress={100} stage={5} />);
    expect(container.querySelectorAll('[data-built="false"]')).toHaveLength(0);
});

it('알 수 없는 준비도를 임의의 진행률로 표시하지 않는다', () => {
    const { container } = render(<BrickHouse progress={NaN} stage={1} />);
    expect(screen.getByRole('img')).toHaveAccessibleName('집 준비도 확인 중');
    expect(container.querySelectorAll('[data-built="true"]')).toHaveLength(0);
});

it('같은 화면의 여러 집이 각각 독립된 SVG 색상 참조를 사용한다', () => {
    const { container } = render(
        <>
            <BrickHouse progress={35} stage={2} />
            <BrickHouse progress={100} stage={5} />
        </>,
    );
    const ids = Array.from(container.querySelectorAll('[id]')).map(
        (item) => item.id,
    );
    expect(new Set(ids).size).toBe(ids.length);
    for (const svg of Array.from(container.querySelectorAll('svg'))) {
        for (const item of Array.from(svg.querySelectorAll('[fill^="url("]'))) {
            const id = item.getAttribute('fill')!.slice(5, -1);
            expect(
                Array.from(svg.querySelectorAll('[id]')).some(
                    (target) => target.id === id,
                ),
            ).toBe(true);
        }
    }
});
