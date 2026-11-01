import styles from './Product.module.css';
export function cx(value: string) {
    return value
        .split(' ')
        .map((name) => styles[name] ?? name)
        .join(' ');
}
