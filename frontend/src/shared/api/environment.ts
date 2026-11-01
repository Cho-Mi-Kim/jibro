// Only the dedicated public demo build may simulate APIs in production.
export const pagesDemo = import.meta.env.MODE === 'pages-demo';
