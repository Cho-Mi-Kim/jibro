import assert from 'node:assert/strict';
import test from 'node:test';
import { analyzeArchitecture, collectImports } from './check-boundaries.mjs';
import {
    collectCssImports,
    checkStyleContract,
    selectorContract,
    findValueCollisions,
} from './check-style-boundaries.mjs';

test('정적·동적·재export·타입 import를 모두 수집한다', () => {
    assert.deepEqual(
        collectImports(
            'module.ts',
            `
        import type { A } from './a';
        import { type B } from './b';
        import { type C, value } from './c';
        export { value } from './d';
        export type { E } from './e';
        const load = () => import('./f');
        type G = import('./g').G;
    `,
        ),
        [
            { specifier: './a', typeOnly: true },
            { specifier: './b', typeOnly: true },
            { specifier: './c', typeOnly: false },
            { specifier: './d', typeOnly: false },
            { specifier: './e', typeOnly: true },
            { specifier: './f', typeOnly: false },
            { specifier: './g', typeOnly: true },
        ],
    );
});

test('타입만 참조해도 shared→기능 및 기능→앱은 거부한다', () => {
    const errors = analyzeArchitecture(
        new Map([
            [
                'shared/ui.ts',
                [{ target: 'features/housing/types.ts', typeOnly: true }],
            ],
            [
                'features/auth/session.ts',
                [{ target: 'app/context.ts', typeOnly: false }],
            ],
        ]),
    );
    assert.equal(errors.length, 2);
    assert.ok(errors.every((error) => error.startsWith('계층 역참조:')));
});

test('기능 간 실행 순환은 거부하고 허용된 하향·타입 의존은 보존한다', () => {
    const cycle = new Map([
        ['features/a.ts', [{ target: 'features/b.ts', typeOnly: false }]],
        ['features/b.ts', [{ target: 'features/a.ts', typeOnly: false }]],
    ]);
    assert.ok(
        analyzeArchitecture(cycle).some((error) =>
            error.startsWith('실행 모듈 순환:'),
        ),
    );
    cycle.set('features/b.ts', [{ target: 'features/a.ts', typeOnly: true }]);
    cycle.set('app/main.ts', [{ target: 'features/a.ts', typeOnly: false }]);
    assert.deepEqual(analyzeArchitecture(cycle), []);
});

test('CSS @value·@import·composes 의존을 수집하고 주석은 제외한다', () => {
    assert.deepEqual(
        collectCssImports(`
        /* @import './ignored.css'; */
        @value row, panel as card from './PrimitivesClassNames.module.css';
        @import url("./theme.css") screen;
        @import url(./plain.css);
        .button { composes: base from './base.module.css'; }
    `),
        [
            {
                kind: 'value',
                specifier: './PrimitivesClassNames.module.css',
                names: ['row', 'panel'],
                localNames: ['row', 'card'],
            },
            { kind: 'import', specifier: './theme.css', names: [] },
            { kind: 'import', specifier: './plain.css', names: [] },
            { kind: 'composes', specifier: './base.module.css', names: [] },
        ],
    );
});

test('ClassNames 계약은 빈 클래스만 허용하고 선언·import·중첩 규칙은 거부한다', () => {
    const file = 'features/home/HomeClassNames.module.css';
    const valid = '/* names only */ .row {} .home-heading { }';
    assert.equal(selectorContract(valid).valid, true);
    assert.deepEqual(
        [...selectorContract(valid).names],
        ['row', 'home-heading'],
    );
    for (const text of [
        '.row { color: red; }',
        '@import "./real.css";',
        '@media (min-width: 768px) { .row {} }',
    ])
        assert.equal(
            checkStyleContract(file, text, collectCssImports(text)).length,
            1,
        );
});

test('@value는 스타일 본문이 없는 ClassNames 계약에서만 이름을 가져온다', () => {
    for (const text of [
        '@value row from "./Primitives.module.css";',
        '@value accent: red;',
    ])
        assert.equal(
            checkStyleContract(
                'features/home/Home.module.css',
                text,
                collectCssImports(text),
            ).length,
            1,
        );
    const valid =
        '@value row from "../../shared/styles/PrimitivesClassNames.module.css";';
    assert.deepEqual(
        checkStyleContract(
            'features/home/Home.module.css',
            valid,
            collectCssImports(valid),
        ),
        [],
    );
});

test('CSS도 공통→기능 역참조와 import 순환을 거부한다', () => {
    const modules = new Map([
        [
            'shared/theme.css',
            [{ target: 'features/home/Home.module.css', typeOnly: false }],
        ],
        [
            'features/home/Home.module.css',
            [{ target: 'shared/theme.css', typeOnly: false }],
        ],
    ]);
    const errors = analyzeArchitecture(modules);
    assert.ok(errors.some((error) => error.startsWith('계층 역참조:')));
    assert.ok(errors.some((error) => error.startsWith('실행 모듈 순환:')));
});

test('@value button이 페이지 이동 버튼 태그나 중첩 선택자를 치환하면 거부한다', () => {
    const text = `
        @value button from './PrimitivesClassNames.module.css';
        .button { color: red; }
        .pagination button { background: none; }
        @media (max-width: 767px) { .toolbar :is(button, a) { margin: 0; } }
    `;
    const collisions = findValueCollisions(text, collectCssImports(text));
    assert.equal(collisions.length, 2);
    assert.ok(
        collisions.every(
            (item) => item.name === 'button' && item.kind === '태그 선택자',
        ),
    );
    assert.equal(
        checkStyleContract(
            'shared/styles/Primitives.module.css',
            text,
            collectCssImports(text),
        ).length,
        2,
    );
});

test('클래스·ID·속성·문자열은 태그로 취급하지 않으며 @value 별칭도 검사한다', () => {
    const safe = `
        @value primary-button from './PrimitivesClassNames.module.css';
        .primary-button, #primary-button, [data-kind=primary-button] {
            content: 'primary-button; { }';
            --button-text: "primary-button";
        }
        .pagination button { background: none; }
    `;
    assert.deepEqual(findValueCollisions(safe, collectCssImports(safe)), []);
    const alias =
        '@value primary-button as button from "./PrimitivesClassNames.module.css"; .pagination button {}';
    assert.equal(
        findValueCollisions(alias, collectCssImports(alias))[0].name,
        'button',
    );
});

test('@value 이름과 CSS 선언 키워드가 겹쳐도 거부한다', () => {
    const text =
        '@value row from "./PrimitivesClassNames.module.css"; .row { flex-direction: row; }';
    assert.deepEqual(findValueCollisions(text, collectCssImports(text)), [
        { name: 'row', kind: '선언 값', text: 'row' },
    ]);
});
