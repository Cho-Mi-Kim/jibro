import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import ts from 'typescript';
import {
    collectCssImports,
    checkStyleContract,
    selectorContract,
} from './check-style-boundaries.mjs';

const normalize = (file) => file.replaceAll('\\', '/');

export function collectImports(file, text) {
    const source = ts.createSourceFile(
        file,
        text,
        ts.ScriptTarget.Latest,
        true,
    );
    const dependencies = [];
    function visit(node) {
        if (
            ts.isImportDeclaration(node) &&
            ts.isStringLiteral(node.moduleSpecifier)
        ) {
            const clause = node.importClause;
            const named = clause?.namedBindings;
            const typeOnly =
                Boolean(clause?.isTypeOnly) ||
                Boolean(
                    !clause?.name &&
                    named &&
                    ts.isNamedImports(named) &&
                    named.elements.length > 0 &&
                    named.elements.every((element) => element.isTypeOnly),
                );
            dependencies.push({
                specifier: node.moduleSpecifier.text,
                typeOnly,
            });
        } else if (
            ts.isExportDeclaration(node) &&
            node.moduleSpecifier &&
            ts.isStringLiteral(node.moduleSpecifier)
        ) {
            const named = node.exportClause;
            const typeOnly =
                node.isTypeOnly ||
                Boolean(
                    named &&
                    ts.isNamedExports(named) &&
                    named.elements.length > 0 &&
                    named.elements.every((element) => element.isTypeOnly),
                );
            dependencies.push({
                specifier: node.moduleSpecifier.text,
                typeOnly,
            });
        } else if (
            ts.isCallExpression(node) &&
            node.expression.kind === ts.SyntaxKind.ImportKeyword
        ) {
            const specifier = node.arguments[0];
            if (specifier && ts.isStringLiteral(specifier))
                dependencies.push({
                    specifier: specifier.text,
                    typeOnly: false,
                });
        } else if (
            ts.isImportTypeNode(node) &&
            ts.isLiteralTypeNode(node.argument) &&
            ts.isStringLiteral(node.argument.literal)
        ) {
            dependencies.push({
                specifier: node.argument.literal.text,
                typeOnly: true,
            });
        }
        ts.forEachChild(node, visit);
    }
    visit(source);
    return dependencies;
}

export function analyzeArchitecture(modules) {
    const errors = [];
    const rank = { app: 3, pages: 2, features: 1, shared: 0 };
    for (const [file, dependencies] of modules) {
        const layer = file.split('/')[0];
        for (const { target } of dependencies) {
            const targetLayer = target.split('/')[0];
            if (rank[layer] !== undefined && rank[targetLayer] > rank[layer])
                errors.push(`계층 역참조: ${file} → ${target}`);
        }
    }
    const active = new Set();
    const visited = new Set();
    function visit(file, route) {
        if (active.has(file)) {
            errors.push(
                `실행 모듈 순환: ${[...route.slice(route.indexOf(file)), file].join(' → ')}`,
            );
            return;
        }
        if (visited.has(file)) return;
        active.add(file);
        for (const { target, typeOnly } of modules.get(file) ?? [])
            if (!typeOnly && modules.has(target))
                visit(target, [...route, file]);
        active.delete(file);
        visited.add(file);
    }
    for (const file of modules.keys()) visit(file, []);
    return errors;
}

export function checkProject(frontendRoot) {
    const configPath = path.join(frontendRoot, 'tsconfig.app.json');
    const config = ts.readConfigFile(configPath, ts.sys.readFile);
    if (config.error)
        throw new Error(
            ts.flattenDiagnosticMessageText(config.error.messageText, '\n'),
        );
    const parsed = ts.parseJsonConfigFileContent(
        config.config,
        ts.sys,
        frontendRoot,
    );
    const sourceRoot = path.join(frontendRoot, 'src');
    const relative = (file) => normalize(path.relative(sourceRoot, file));
    const files = new Set(parsed.fileNames.map((file) => path.resolve(file)));
    const styles = new Map();
    function collectStyles(directory) {
        for (const entry of fs.readdirSync(directory, {
            withFileTypes: true,
        })) {
            const file = path.join(directory, entry.name);
            if (entry.isDirectory()) collectStyles(file);
            else if (file.endsWith('.css'))
                styles.set(file, fs.readFileSync(file, 'utf8'));
        }
    }
    collectStyles(sourceRoot);
    const modules = new Map();
    const errors = [];
    for (const file of files) {
        const dependencies = [];
        for (const item of collectImports(
            file,
            fs.readFileSync(file, 'utf8'),
        )) {
            const cssTarget = item.specifier.startsWith('.')
                ? path.resolve(path.dirname(file), item.specifier)
                : null;
            const target =
                cssTarget && styles.has(cssTarget)
                    ? cssTarget
                    : ts.resolveModuleName(
                          item.specifier,
                          file,
                          parsed.options,
                          ts.sys,
                      ).resolvedModule?.resolvedFileName;
            if (
                target &&
                (files.has(path.resolve(target)) ||
                    styles.has(path.resolve(target)))
            )
                dependencies.push({
                    target: relative(target),
                    typeOnly: item.typeOnly,
                });
        }
        modules.set(relative(file), dependencies);
    }
    for (const [file, text] of styles) {
        const imports = collectCssImports(text);
        errors.push(...checkStyleContract(relative(file), text, imports));
        const dependencies = [];
        for (const item of imports) {
            if (!item.specifier?.startsWith('.')) continue;
            const target = path.resolve(path.dirname(file), item.specifier);
            if (!styles.has(target)) {
                errors.push(
                    `CSS 의존 파일 없음: ${relative(file)} → ${item.specifier}`,
                );
                continue;
            }
            dependencies.push({ target: relative(target), typeOnly: false });
            if (
                item.kind === 'value' &&
                target.endsWith('ClassNames.module.css')
            ) {
                const contract = selectorContract(styles.get(target));
                for (const name of item.names)
                    if (!contract.names.has(name))
                        errors.push(
                            `CSS 선택자 계약 불일치: ${relative(file)} → ${relative(target)}의 ${name}`,
                        );
            }
        }
        modules.set(relative(file), dependencies);
    }
    errors.push(...analyzeArchitecture(modules));
    const entry = path.join(sourceRoot, 'main.tsx');
    if (
        collectImports(entry, fs.readFileSync(entry, 'utf8'))[0]?.specifier !==
        './app/presentationStyles'
    )
        errors.push(
            'CSS 적용 순서: main.tsx의 첫 import는 ./app/presentationStyles여야 합니다.',
        );
    for (const name of ['tsconfig.app.json', 'tsconfig.node.json']) {
        const source = ts.readConfigFile(
            path.join(frontendRoot, name),
            ts.sys.readFile,
        );
        if (source.error)
            throw new Error(
                ts.flattenDiagnosticMessageText(source.error.messageText, '\n'),
            );
        const options = ts.parseJsonConfigFileContent(
            source.config,
            ts.sys,
            frontendRoot,
        ).options;
        if (options.strict !== true)
            errors.push(
                `엄격한 타입 검사 누락: ${name}의 strict 설정이 필요합니다.`,
            );
    }
    return { modules: files.size, styles: styles.size, errors };
}

if (
    process.argv[1] &&
    import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href
) {
    const frontendRoot = path.resolve(
        path.dirname(fileURLToPath(import.meta.url)),
        '..',
    );
    const result = checkProject(frontendRoot);
    if (result.errors.length) {
        for (const error of result.errors) process.stderr.write(error + '\n');
        process.exitCode = 1;
    } else
        process.stdout.write(
            `프런트 TS ${result.modules}개·CSS ${result.styles}개: 계층·순환·선택자 계약 검사 통과.\n`,
        );
}
