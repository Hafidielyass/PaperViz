# ---- build ----
FROM node:22-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
# BuildKit cache mount: the npm cache survives across builds, so a network
# failure part-way through does not mean re-downloading everything on retry.
# This machine's connection drops often enough that it matters.
RUN --mount=type=cache,target=/root/.npm     npm ci --prefer-offline --no-audit --fund=false

COPY . .
RUN npm run build -- --configuration production

# ---- runtime ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist/paperviz-web/browser /usr/share/nginx/html
EXPOSE 80
