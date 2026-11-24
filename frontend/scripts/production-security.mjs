import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

export function internalArtifact(name) {
    return /^(?:review-[^/]+\.html|jibro-code-model\.html)$/.test(name);
}

export function scriptHashes(html) {
    const normalized = html.replace(/\r\n?/g, '\n');
    const markup = normalized.replace(
        /<script\b[^>]*>[\s\S]*?<\/script\s*>/gi,
        '',
    );
    if (
        /<[a-z][^>]*\son[a-z]+\s*=/i.test(markup) ||
        /(?:href|src)\s*=\s*["']\s*javascript:/i.test(markup)
    )
        throw new Error(
            'Move HTML event handlers and javascript URLs into a trusted module',
        );
    const hashes = [];
    for (const [, attributes, source] of normalized.matchAll(
        /<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi,
    )) {
        if (!/\bsrc\s*=/i.test(attributes) && source.trim())
            hashes.push(
                `'sha256-${crypto.createHash('sha256').update(source).digest('base64')}'`,
            );
    }
    return hashes;
}

export function securityHeaders(hashes) {
    const policy = [
        "default-src 'self'",
        "base-uri 'none'",
        "object-src 'none'",
        "frame-ancestors 'none'",
        "form-action 'self'",
        `script-src 'self' https://challenges.cloudflare.com https://www.googletagmanager.com ${[...new Set(hashes)].sort().join(' ')}`.trim(),
        "script-src-attr 'none'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: blob: https:",
        "font-src 'self' data:",
        "connect-src 'self' https://challenges.cloudflare.com https://www.googletagmanager.com https://*.google-analytics.com https://*.analytics.google.com https://analytics.google.com",
        "frame-src 'self' https://challenges.cloudflare.com https://www.googletagmanager.com",
        "worker-src 'self' blob:",
        "manifest-src 'self'",
        "media-src 'self' blob:",
        'upgrade-insecure-requests',
    ].join('; ');
    return `# Generated from the release HTML; never weaken script-src for a new viewer.\nadd_header Content-Security-Policy "${policy}" always;\nadd_header Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=(), usb=()" always;\nadd_header Cross-Origin-Resource-Policy same-origin always;\n`;
}

export function productionSecurityPlugin() {
    let output;
    return {
        name: 'jibro-production-security',
        apply: 'build',
        configResolved(config) {
            output = path.resolve(config.root, config.build.outDir);
        },
        closeBundle() {
            const hashes = [];
            for (const entry of fs.readdirSync(output, {
                recursive: true,
                withFileTypes: true,
            })) {
                if (!entry.isFile()) continue;
                const filename = path.join(entry.parentPath, entry.name);
                if (
                    internalArtifact(
                        path.relative(output, filename).replaceAll('\\', '/'),
                    )
                ) {
                    fs.unlinkSync(filename);
                } else if (entry.name.endsWith('.html')) {
                    const html = fs
                        .readFileSync(filename, 'utf8')
                        .replace(/\r\n?/g, '\n');
                    hashes.push(...scriptHashes(html));
                    fs.writeFileSync(filename, html);
                }
            }
            fs.writeFileSync(
                path.join(output, 'security-headers.conf'),
                securityHeaders(hashes),
            );
        },
    };
}
