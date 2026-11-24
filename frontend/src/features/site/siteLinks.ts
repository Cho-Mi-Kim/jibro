export function sitePageHref(page: 'contact' | 'privacy') {
    return import.meta.env.MODE === 'pages-demo'
        ? `${import.meta.env.BASE_URL}${page}.html`
        : `/${page}`;
}
