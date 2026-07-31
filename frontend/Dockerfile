# Node 版本必须与 package.json 的 engines 和 CI 保持一致（三处同为 22）。
# 此前这里是 24 而 engines 要求 >=20 <23，构建用的运行时根本不在支持范围内。
FROM node:22-alpine AS build
WORKDIR /app
ARG VITE_DEMO_REPO_PATH=/app/demo-repos/mall-order-service
ENV VITE_DEMO_REPO_PATH=$VITE_DEMO_REPO_PATH
COPY package.json package-lock.json ./
# npm ci 严格按锁文件安装，并在 package.json 与锁文件不一致时直接失败；
# npm install 会就地改写锁文件，让镜像构建不可复现。
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.30.4-alpine
COPY --from=build /app/dist /usr/share/nginx/html
