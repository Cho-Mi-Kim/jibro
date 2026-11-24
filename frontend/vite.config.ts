import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';
import { loadEnv } from 'vite';
import { publicSitePlugin } from './scripts/public-site.mjs';
import { productionSecurityPlugin } from './scripts/production-security.mjs';

function analyticsConfigured(mode: string) {
    const environment = loadEnv(mode, process.cwd(), 'VITE_');
    return (
        /^G-[A-Z0-9]+$/.test(environment.VITE_GA_MEASUREMENT_ID ?? '') &&
        /^GTM-[A-Z0-9]+$/.test(environment.VITE_GA_TAG_ID ?? '')
    );
}

export default defineConfig(({ mode }) => ({
    base: mode === 'pages-demo' ? '/jibro/' : '/',
    plugins: [
        react(),
        publicSitePlugin({
            analytics: analyticsConfigured(mode),
        }),
        productionSecurityPlugin(),
    ],
    server: {
        host: '127.0.0.1',
        port: 5173,
        strictPort: true,
        fs: {
            deny: [
                '.env',
                '.env.*',
                '*.{crt,pem}',
                '**/.git/**',
                '**/.tools/**',
                '**/*.eml',
            ],
        },
        proxy: {
            '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
            '/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: true },
            '/login/oauth2': {
                target: 'http://127.0.0.1:8080',
                changeOrigin: true,
            },
        },
    },
    test: {
        server: { deps: { inline: [/@seed-design\//] } },
        environment: 'jsdom',
        setupFiles: ['./src/test/setup.ts'],
        clearMocks: true,
    },
}));
