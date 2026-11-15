import primitives from './Primitives.module.css';

/** Resolve shared primitives and the caller's own CSS module classes. */
export function bindClasses(...modules: Readonly<Record<string, string>>[]) {
    const classes: Record<string, string> = { ...primitives };
    for (const module of modules) Object.assign(classes, module);
    return (value: string) =>
        value
            .split(' ')
            .map((name) => classes[name] ?? name)
            .join(' ');
}

export const cx = bindClasses();
