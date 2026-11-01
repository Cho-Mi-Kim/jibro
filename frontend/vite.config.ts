import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => ({
    define:
        mode === 'demo'
            ? { 'import.meta.env.VITE_API_MODE': JSON.stringify('demo') }
            : {},
    plugins: [react()],
    server: {
        host: '127.0.0.1',
        port: 5173,
        strictPort: true,
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
