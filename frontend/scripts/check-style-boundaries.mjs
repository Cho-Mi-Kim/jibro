// Only dependency directives and the deliberately restricted selector contract
// are parsed here. Vite remains responsible for general CSS syntax/compilation.
const withoutComments = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '');

export function collectCssImports(text) {
    const source = withoutComments(text);
    const dependencies = [];
    for (const match of source.matchAll(/@(value|import)\b([^;{}]*);/g)) {
        const [, kind, params] = match;
        const reference =
            kind === 'value'
                ? params.match(/\bfrom\s+(['"])([^'"]+)\1\s*$/)
                : params.match(/(?:url\(\s*)?(['"])([^'"]+)\1/);
        const unquotedUrl =
            kind === 'import'
                ? params.match(/url\(\s*([^\s'"()]+)\s*\)/)?.[1]
                : null;
        const bindings =
            kind === 'value' && reference
                ? params
                      .slice(0, reference.index)
                      .split(',')
                      .map((name) => name.trim().split(/\s+as\s+/))
                : [];
        dependencies.push({
            kind,
            specifier: reference?.[2] ?? unquotedUrl ?? null,
            names: bindings.map(([name]) => name),
            ...(kind === 'value'
                ? { localNames: bindings.map(([name, alias]) => alias ?? name) }
                : {}),
        });
    }
    for (const match of source.matchAll(
        /\bcomposes\s*:[^;{}]*?\bfrom\s+(['"])([^'"]+)\1\s*;/g,
    ))
        dependencies.push({ kind: 'composes', specifier: match[2], names: [] });
    return dependencies;
}

function cssUseSites(text) {
    const source = withoutComments(text);
    const sites = [];
    let start = 0;
    let depth = 0;
    let parentheses = 0;
    let quote = null;
    function declaration(end) {
        if (!depth) return;
        const match = source
            .slice(start, end)
            .trim()
            .match(/^[\w-]+\s*:\s*([\s\S]+)$/);
        if (match) sites.push({ kind: '선언 값', text: match[1] });
    }
    for (let index = 0; index < source.length; index++) {
        const character = source[index];
        if (character === '\\') {
            index++;
            continue;
        }
        if (quote) {
            if (character === quote) quote = null;
            continue;
        }
        if (character === '"' || character === "'") {
            quote = character;
            continue;
        }
        if (character === '(') parentheses++;
        else if (character === ')') parentheses--;
        if (parentheses) continue;
        if (character === '{') {
            const selector = source.slice(start, index).trim();
            if (selector && !selector.startsWith('@'))
                sites.push({ kind: '태그 선택자', text: selector });
            depth++;
            start = index + 1;
        } else if (character === ';' || character === '}') {
            declaration(index);
            if (character === '}') depth--;
            start = index + 1;
        }
    }
    return sites;
}

function bareNames(text) {
    // Attribute values and quoted strings are not type selectors/keywords.
    const source = text
        .replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, '')
        .replace(/\[[^\]]*\]/g, '');
    return [...source.matchAll(/[a-zA-Z_-][\w-]*/g)]
        .filter((match) => !/[.#:\\]/.test(source[match.index - 1] ?? ''))
        .map((match) => match[0]);
}

export function findValueCollisions(text, imports) {
    const importedNames = new Set(
        imports
            .filter((item) => item.kind === 'value')
            .flatMap((item) => item.localNames ?? item.names),
    );
    return cssUseSites(text).flatMap((site) =>
        [...new Set(bareNames(site.text))]
            .filter((name) => importedNames.has(name))
            .map((name) => ({ name, ...site })),
    );
}

export function selectorContract(text) {
    const source = withoutComments(text);
    const selectors = [...source.matchAll(/\.([a-zA-Z_][\w-]*)\s*\{\s*\}/g)];
    return {
        valid:
            source.replace(/\.([a-zA-Z_][\w-]*)\s*\{\s*\}/g, '').trim() === '',
        names: new Set(selectors.map((match) => match[1])),
    };
}

export function checkStyleContract(file, text, imports) {
    const errors = [];
    if (file.endsWith('ClassNames.module.css') && !selectorContract(text).valid)
        errors.push(
            `선택자 계약에 스타일 선언 금지: ${file}에는 빈 클래스 규칙만 둘 수 있습니다.`,
        );
    for (const item of imports) {
        if (
            item.kind === 'value' &&
            (!item.specifier?.startsWith('.') ||
                !item.specifier.endsWith('ClassNames.module.css'))
        )
            errors.push(
                `스타일 본문 재적용 방지: ${file}의 @value는 ClassNames.module.css만 참조해야 합니다.`,
            );
    }
    for (const collision of findValueCollisions(text, imports))
        errors.push(
            `CSS 이름 치환 충돌: ${file}의 @value ${collision.name}이 ${collision.kind}에도 쓰입니다 (${collision.text.trim()}). 클래스 계약 이름을 바꾸세요.`,
        );
    return errors;
}
