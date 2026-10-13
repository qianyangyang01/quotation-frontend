FROM node:22-alpine AS build
WORKDIR /workspace
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:1.27-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY --from=build /workspace/dist/assets /opt/quotation/assets
COPY deploy/frontend/retain-assets.sh /opt/quotation/retain-assets.sh
RUN sed -i 's/\r$//' /opt/quotation/retain-assets.sh \
    && chmod 0755 /opt/quotation/retain-assets.sh \
    && ln -s /opt/quotation/retain-assets.sh /docker-entrypoint.d/25-retain-quotation-assets.sh
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
