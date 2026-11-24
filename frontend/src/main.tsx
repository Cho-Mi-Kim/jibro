import './app/presentationStyles';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AppRouter } from './app/AppRouter';
import { QueryClientProvider } from '@tanstack/react-query';
import { App } from './app/App';
import { queryClient } from './app/queryClient';
import { configureTransport } from './app/configureTransport';
import './shared/styles/global.css';
import { VisitorGate } from './features/security/VisitorGate';
import { AnalyticsConsent } from './features/analytics/AnalyticsConsent';
import { SiteMetadata } from './features/site/SiteMetadata';

configureTransport();
const root = document.getElementById('root');
if (!root) throw new Error('앱을 표시할 영역을 찾지 못했습니다.');
createRoot(root).render(
    <StrictMode>
        <QueryClientProvider client={queryClient}>
            <VisitorGate>
                <AppRouter>
                    <SiteMetadata />
                    <AnalyticsConsent />
                    <App />
                </AppRouter>
            </VisitorGate>
        </QueryClientProvider>
    </StrictMode>,
);
