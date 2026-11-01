export function safeReturn(value: string | null) {
    return value?.startsWith('/') &&
        !value.startsWith('//') &&
        !value.includes('\\') &&
        !value.startsWith('/login') &&
        !value.startsWith('/auth/')
        ? value
        : '/preparations';
}
