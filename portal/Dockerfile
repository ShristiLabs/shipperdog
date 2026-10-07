# Portal container (issue #35): build with Node 22, serve the static
# bundle with nginx. The nginx config (portal/nginx.conf) proxies
# /api to the api service so the browser sees one origin — no CORS, no
# environment-specific API base URL in the client. Kept separate from
# the console's container and bundle (issue AC1).
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
