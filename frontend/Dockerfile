FROM node:24-alpine AS build
WORKDIR /app
ARG VITE_DEMO_REPO_PATH=/app/demo-repos/mall-order-service
ENV VITE_DEMO_REPO_PATH=$VITE_DEMO_REPO_PATH
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
