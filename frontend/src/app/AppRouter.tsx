import type { ReactNode } from 'react';
import { BrowserRouter, HashRouter } from 'react-router';
import { pagesDemo } from '../shared/api/environment';

export function AppRouter({ children }: { children: ReactNode }) {
    const Router = pagesDemo ? HashRouter : BrowserRouter;
    return <Router>{children}</Router>;
}
