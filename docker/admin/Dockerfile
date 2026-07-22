FROM node:22.14-alpine AS builder

WORKDIR /build
COPY aimall-admin/package.json aimall-admin/package-lock.json ./
RUN npm config set registry https://registry.npmmirror.com \
    && npm config set fetch-retries 5 \
    && npm config set fetch-retry-mintimeout 20000 \
    && npm config set fetch-retry-maxtimeout 120000 \
    && npm ci --include=dev --legacy-peer-deps --no-audit
COPY aimall-admin/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY --from=builder /build/dist /usr/share/nginx/html
COPY docker/admin/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 5174
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:5174/ || exit 1
CMD ["nginx", "-g", "daemon off;"]
