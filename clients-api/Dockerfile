# syntax=docker/dockerfile:1

# --- build -------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app

# Primero solo los manifiestos: la capa de dependencias se reutiliza si no cambian.
COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

# --- runtime -----------------------------------------------------------------
FROM node:22-alpine
ENV NODE_ENV=production \
    PORT=8080
WORKDIR /app

COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist

# Usuario sin privilegios incluido en la imagen oficial de Node.
USER node
EXPOSE 8080

# La imagen no tiene curl: el healthcheck usa el fetch de Node.
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
    CMD ["node", "dist/healthcheck.js"]

# node directamente (no "npm start"): recibe SIGTERM y ejecuta el apagado controlado.
CMD ["node", "dist/main.js"]
