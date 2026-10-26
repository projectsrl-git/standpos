# syntax=docker/dockerfile:1
# StandPOS web: the PWA build served by Caddy, which also terminates TLS and proxies the API.
# Build context: repository root.
#   docker buildx build -f frontend/Dockerfile --platform linux/amd64,linux/arm64 .

# Static assets are platform-independent: build once on the native platform.
FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM caddy:2-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/dist /srv
