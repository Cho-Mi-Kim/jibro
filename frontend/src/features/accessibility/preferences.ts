export type AccessibilityPreferences = {
    theme: 'light' | 'dark';
    textSize: 'default' | 'large' | 'extra';
    highContrast: boolean;
    reduceMotion: boolean;
};

export const defaultPreferences: AccessibilityPreferences = {
    theme: 'light',
    textSize: 'default',
    highContrast: false,
    reduceMotion: false,
};
export const preferenceKey = 'jibro.accessibility.v1';

export function parsePreferences(
    value: string | null,
): AccessibilityPreferences {
    try {
        const data: unknown = JSON.parse(value ?? 'null');
        if (!data || typeof data !== 'object') return defaultPreferences;
        const input = data as Record<string, unknown>;
        return {
            theme: input.theme === 'dark' ? 'dark' : 'light',
            textSize:
                input.textSize === 'large' || input.textSize === 'extra'
                    ? input.textSize
                    : 'default',
            highContrast: input.highContrast === true,
            reduceMotion: input.reduceMotion === true,
        };
    } catch {
        return defaultPreferences;
    }
}

export function readPreferences() {
    try {
        return {
            preferences: parsePreferences(localStorage.getItem(preferenceKey)),
            persistent: true,
        };
    } catch {
        return { preferences: defaultPreferences, persistent: false };
    }
}

export { prefersReducedMotion } from '../../shared/ui/motionPreferences';
