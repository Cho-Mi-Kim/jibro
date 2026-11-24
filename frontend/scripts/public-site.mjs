import fs from 'node:fs';
import path from 'node:path';
import ts from 'typescript';
import { fileURLToPath } from 'node:url';

const root = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    '../..',
);
export const site = JSON.parse(
    fs.readFileSync(path.join(root, 'data/site/public-site.json'), 'utf8'),
);
const escape = (value) =>
    String(value).replace(
        /[&<>"']/g,
        (ch) =>
            ({
                '&': '&amp;',
                '<': '&lt;',
                '>': '&gt;',
                '"': '&quot;',
                "'": '&#39;',
            })[ch],
    );
const link = ([label, url]) =>
    `<a href="${escape(url)}"${url.startsWith('https://') ? ' rel="noopener noreferrer"' : ''}>${escape(label)}</a>`;
const brandCss = `header{height:86px;display:flex;align-items:center;padding:0 max(28px,calc((100vw - 1440px)/2));border-bottom:1px solid #e5e5e8;background:#fff}header a{display:flex;align-items:center;min-height:44px;gap:12px;color:inherit;font-family:'Pretendard',-apple-system,BlinkMacSystemFont,'Segoe UI','Malgun Gothic',sans-serif;font-size:2rem;font-weight:800;letter-spacing:-1.5px;line-height:1.55;text-decoration:none;white-space:nowrap}header svg{display:block;flex:none;width:44px;height:44px;color:#b64a32;stroke-width:3}header a:focus-visible{outline:3px solid #805b00;outline-offset:4px}@media(max-width:767px){header{height:auto;min-height:4rem;padding:.5rem clamp(1rem,5vw,1.25rem);background:transparent;border-bottom:0}header a{font-size:1.6875rem}header svg{width:34px;height:34px}}@media(min-width:768px) and (max-width:1023px){header{height:auto;min-height:4.5rem;padding:.5rem clamp(1rem,5vw,1.25rem)}header a{font-size:1.5rem}}@media(prefers-color-scheme:dark){header{background:#242220;border-color:#625a54}header svg{color:#f0a38b}@media(max-width:767px){header{background:transparent}}}`;
const css = `:root{font-family:system-ui,"Malgun Gothic",sans-serif;color:#262626;background:#faf8f5;line-height:1.75}*{box-sizing:border-box}body{margin:0}main,footer{max-width:880px;margin:auto;padding:20px}a{color:#9b3d28;text-underline-offset:4px;overflow-wrap:anywhere}h1{font-size:clamp(24px,5vw,32px);line-height:1.4}h2{font-size:21px;line-height:1.5;margin-top:32px}p{margin:12px 0}section{background:#fff;border:1px solid #e4dfda;border-radius:16px;padding:clamp(16px,4vw,28px);margin:20px 0}.muted{color:#626262;font-size:14px}.table{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:15px;text-align:left}th,td{padding:12px;border-bottom:1px solid #e4dfda;vertical-align:top;min-width:110px;overflow-wrap:anywhere}th{background:#f5eee9}ul{padding-left:24px}li{margin:8px 0}.actions{display:flex;gap:12px;flex-wrap:wrap}.actions a{display:inline-flex;align-items:center;min-height:44px;padding:8px 16px;border:1px solid #b64a32;border-radius:12px;text-decoration:none}.actions a:first-child{background:#b64a32;color:#fff}footer{display:flex;gap:16px;flex-wrap:wrap;border-top:1px solid #e4dfda;margin-top:32px;font-size:14px}.code{color:#b64a32;font-size:56px;font-weight:800;margin:0}@media(prefers-color-scheme:dark){:root{background:#1b1917;color:#f2efeb}section{background:#25211e}th{background:#332a25}header,footer,section,th,td{border-color:#51483f}a{color:#f1a38f}.muted{color:#c8bfb7}}`;

export function metadata(
    title = site.title,
    description = site.description,
    pathname = '/',
) {
    return `<meta name="description" content="${escape(description)}"><link rel="canonical" href="${site.origin}${pathname}"><meta property="og:type" content="website"><meta property="og:site_name" content="${escape(site.name)}"><meta property="og:title" content="${escape(title)}"><meta property="og:description" content="${escape(description)}"><meta property="og:url" content="${site.origin}${pathname}"><meta name="twitter:card" content="summary">`;
}
function page(title, body, pathname, noindex = false) {
    return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escape(title)} · 집으로</title>${metadata(title + ' · 집으로', site.description, pathname)}${noindex ? '<meta name="robots" content="noindex,follow">' : ''}<style>${css}${brandCss}</style></head><body><header><a href="/" aria-label="집으로 홈"><svg width="44" height="44" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 10 12 3l9 7v11h-7v-7h-4v7H3Z" /></svg>집으로</a></header><main><h1>${escape(title)}</h1>${body}</main><footer>${link(['홈', '/'])}${link(['공고 찾기', '/notices'])}${link(['문의 안내', '/contact'])}${link(['개인정보처리방침', '/privacy'])}${link(['통계 설정', '/my#analytics-settings'])}</footer></body></html>`;
}
export function publicPages(analytics = false, base = '/') {
    const sections = site.privacy
        .filter((s) => !s.analyticsOnly || analytics)
        .map(
            (s) =>
                `<section><h2>${escape(s.title)}</h2>${s.paragraphs.map((p) => `<p>${escape(p)}</p>`).join('')}${s.rows ? `<div class="table" tabindex="0" role="region" aria-label="${escape(s.title)} 표"><table><thead><tr>${s.headers.map((h) => `<th scope="col">${escape(h)}</th>`).join('')}</tr></thead><tbody>${s.rows.map((row) => `<tr>${row.map((c) => `<td>${escape(c)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>` : ''}${s.links ? `<ul>${s.links.map((l) => `<li>${link(l)}</li>`).join('')}</ul>` : ''}</section>`,
        )
        .join('');
    const pages = {
        'privacy.html': page(
            '개인정보처리방침',
            `<p class="muted">시행일 ${site.effectiveDate} · ${escape(site.operator)}</p>${sections}`,
            '/privacy',
        ),
        'contact.html': page(
            '문의 안내',
            `<section><h2>${escape(site.operator)}</h2><p>${link([site.email, 'mailto:' + site.email])}</p><p><strong>답변 시간</strong><br>${escape(site.supportHours)}</p><p><strong>${escape(site.replyTarget)}</strong><br>문의가 많거나 확인이 필요한 경우 시간이 더 걸릴 수 있어요.</p><div class="actions">${link(['이메일로 문의하기', 'mailto:' + site.email])}</div></section><section><h2>이렇게 보내 주세요</h2><ul><li>문의 제목과 문제가 발생한 화면·상황을 알려 주세요.</li><li>주민등록번호·계좌번호·비밀번호와 증빙서류 원본은 보내지 마세요.</li><li>공공주택 자격·선정·계약에 관한 최종 확인은 공고의 공식 문의처로 문의해 주세요.</li></ul></section>`,
            '/contact',
        ),
        '404.html': page(
            '페이지를 찾을 수 없어요',
            `<section><p class="code" aria-hidden="true">404</p><p>주소가 바뀌었거나 더 이상 제공하지 않는 페이지예요.<br>홈이나 공고 목록에서 다시 찾아보세요.</p><div class="actions">${link(['홈으로 이동', '/'])}${link(['공고 찾기', '/notices'])}</div></section>`,
            '/404.html',
            true,
        ),
        'robots.txt': `User-agent: *\nAllow: /\n${['api/', 'admin', 'preparations', 'saved', 'archive', 'alerts', 'settings', 'login', 'signup', 'auth/', 'account/', 'recovery', 'development', 'review-', 'pdf-viewer.html', 'evidence-viewer.html', 'evidence-dynamic.html', 'jibro-code-model.html'].map((p) => 'Disallow: /' + p).join('\n')}\nSitemap: ${site.origin}/sitemap.xml\n`,
        'sitemap.xml': `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">${['/', '/notices', '/guide', '/contact', '/privacy'].map((p) => `<url><loc>${site.origin}${p}</loc><lastmod>${site.effectiveDate}</lastmod></url>`).join('')}</urlset>`,
    };
    if (base !== '/') {
        // Pages serves documents as files and application screens via HashRouter.
        const destinations = new Map([
            ['/', `${base}#/`],
            ['/notices', `${base}#/notices`],
            ['/contact', `${base}contact.html`],
            ['/privacy', `${base}privacy.html`],
            ['/my#analytics-settings', `${base}#/my#analytics-settings`],
        ]);
        for (const name of Object.keys(pages).filter((name) =>
            name.endsWith('.html'),
        )) {
            pages[name] = pages[name].replace(
                /href="([^"]*)"/g,
                (attribute, url) =>
                    destinations.has(url)
                        ? `href="${escape(destinations.get(url))}"`
                        : attribute,
            );
        }
    }
    return pages;
}

export function appRouteConfig(source) {
    const file = ts.createSourceFile(
        'App.tsx',
        source,
        ts.ScriptTarget.Latest,
        true,
        ts.ScriptKind.TSX,
    );
    const routes = new Set();
    function visit(node) {
        if (
            ts.isJsxAttribute(node) &&
            node.name.getText(file) === 'path' &&
            node.initializer &&
            ts.isStringLiteral(node.initializer)
        ) {
            const route = node.initializer.text;
            if (
                route.startsWith('/') &&
                route !== '*' &&
                route !== '/development'
            )
                routes.add(route);
        }
        ts.forEachChild(node, visit);
    }
    visit(file);
    if (!routes.has('/') || !routes.has('/notices'))
        throw new Error('Application routes could not be collected');
    const patterns = [...routes].sort().map((route) =>
        route
            .split('/')
            .map((part) =>
                part.startsWith(':')
                    ? '[A-Za-z0-9-]+'
                    : part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
            )
            .join('/'),
    );
    return `# Generated from App.tsx routes. Do not maintain a second route list.\nlocation ~ ^(?:${patterns.join('|')})/?$ {\n    try_files /index.html =404;\n}\n`;
}

export function publicSitePlugin({ analytics = false } = {}) {
    let base = '/';
    return {
        name: 'jibro-public-site',
        configResolved(config) {
            base = config.mode === 'pages-demo' ? config.base : '/';
        },
        transformIndexHtml(html) {
            return html
                .replace(
                    /<title>.*?<\/title>/,
                    `<title>${escape(site.title)}</title>`,
                )
                .replace('</head>', `${metadata()}</head>`);
        },
        configureServer(server) {
            server.middlewares.use((req, res, next) => {
                const pathname = (req.url ?? '').split('?')[0];
                const name =
                    pathname === '/privacy'
                        ? 'privacy.html'
                        : pathname === '/contact'
                          ? 'contact.html'
                          : pathname.slice(1);
                const pages = publicPages(analytics, base);
                if (!(name in pages)) return next();
                res.statusCode = name === '404.html' ? 404 : 200;
                res.setHeader(
                    'Content-Type',
                    name.endsWith('.txt')
                        ? 'text/plain; charset=utf-8'
                        : name.endsWith('.xml')
                          ? 'application/xml; charset=utf-8'
                          : 'text/html; charset=utf-8',
                );
                res.end(pages[name]);
            });
        },
        generateBundle() {
            for (const [fileName, source] of Object.entries(
                publicPages(analytics, base),
            ))
                this.emitFile({ type: 'asset', fileName, source });
            const source = fs.readFileSync(
                path.join(root, 'frontend/src/app/App.tsx'),
                'utf8',
            );
            this.emitFile({
                type: 'asset',
                fileName: 'app-routes.conf',
                source: appRouteConfig(source),
            });
        },
    };
}
