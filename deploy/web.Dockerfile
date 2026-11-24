ARG NGINX_BASE=nginx:1.30.6-alpine
FROM ${NGINX_BASE}
COPY frontend/ /usr/share/nginx/html/
COPY frontend/app-routes.conf /etc/nginx/snippets/app-routes.conf
COPY frontend/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80 443
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 CMD wget -q --spider http://127.0.0.1:8081/healthz || exit 1
