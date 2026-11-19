# Build context is the repository root (pnpm workspace).
FROM node:22-alpine AS build
RUN corepack enable
WORKDIR /repo
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/
RUN pnpm install --frozen-lockfile --filter @chainbank/web...
COPY apps/web apps/web
# Baked into the /api rewrite at build time.
ARG CHAINBANK_API_URL=http://api:8080
ENV CHAINBANK_API_URL=$CHAINBANK_API_URL
RUN pnpm --filter @chainbank/web build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production PORT=3000 HOSTNAME=0.0.0.0
COPY --from=build --chown=node /repo/apps/web/.next/standalone ./
COPY --from=build --chown=node /repo/apps/web/.next/static ./apps/web/.next/static
USER node
EXPOSE 3000
CMD ["node", "apps/web/server.js"]
