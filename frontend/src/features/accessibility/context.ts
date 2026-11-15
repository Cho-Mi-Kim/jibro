import { createContext, useContext } from 'react';
import type { AccessibilityPreferences } from './preferences';

export const AccessibilityContext = createContext<{
    preferences: AccessibilityPreferences;
    persistent: boolean;
    update: (preferences: AccessibilityPreferences) => void;
} | null>(null);

export function useAccessibility() {
    const context = useContext(AccessibilityContext);
    if (!context) throw new Error('AccessibilityProvider is required');
    return context;
}
