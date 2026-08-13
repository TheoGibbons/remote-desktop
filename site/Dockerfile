FROM nginx:alpine

ARG PUBLIC_SITE_URL=http://localhost:8087

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY public/ /usr/share/nginx/html/

RUN sed -i "s|__PUBLIC_SITE_URL__|${PUBLIC_SITE_URL%/}|g" /usr/share/nginx/html/index.html

EXPOSE 80
