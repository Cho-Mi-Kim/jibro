ARG NGINX_BASE=nginx:1.30.6-alpine
FROM ${NGINX_BASE}
COPY frontend/ /usr/share/nginx/html/
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80 443
