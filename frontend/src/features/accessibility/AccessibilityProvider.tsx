import { useEffect, useLayoutEffect, useState, type ReactNode } from 'react';
import { AccessibilityContext } from './context';
import {
    parsePreferences,
    preferenceKey,
    readPreferences,
    type AccessibilityPreferences,
} from './preferences';
import './accessibility.css';

export function AccessibilityProvider({ children }: { children: ReactNode }) {
    const [state, setState] = useState(readPreferences);
    useLayoutEffect(() => {
        const root = document.documentElement;
        root.dataset.theme = state.preferences.theme;
        root.dataset.textSize = state.preferences.textSize;
        root.dataset.highContrast = String(state.preferences.highContrast);
        root.dataset.reduceMotion = String(state.preferences.reduceMotion);
        return () => {
            delete root.dataset.theme;
            delete root.dataset.textSize;
            delete root.dataset.highContrast;
            delete root.dataset.reduceMotion;
        };
    }, [state.preferences]);
    useEffect(() => {
        const sync = (event: StorageEvent) => {
            if (event.key !== null && event.key !== preferenceKey) return;
            // Other tabs may clear or replace the saved device preferences.
            setState({
                preferences: parsePreferences(event.newValue),
                persistent: true,
            });
        };
        window.addEventListener('storage', sync);
        return () => window.removeEventListener('storage', sync);
    }, []);
    function update(preferences: AccessibilityPreferences) {
        let persistent = true;
        try {
            localStorage.setItem(preferenceKey, JSON.stringify(preferences));
        } catch {
            persistent = false;
        }
        setState({ preferences, persistent });
    }
    return (
        <AccessibilityContext.Provider value={{ ...state, update }}>
            {children}
        </AccessibilityContext.Provider>
    );
}
