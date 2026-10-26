FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Rewrites (/api/* -> backend) are fixed at build time.
ARG BACKEND_URL=http://api:8080
ENV BACKEND_URL=$BACKEND_URL NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN adduser -S -u 10001 waslah
COPY --from=build --chown=waslah /src/.next/standalone ./
COPY --from=build --chown=waslah /src/.next/static ./.next/static
USER waslah
EXPOSE 3000
CMD ["node", "server.js"]
