# syntax=docker/dockerfile:1

# --- build -------------------------------------------------------------------
FROM golang:1.22-alpine AS build
WORKDIR /src

# Primero solo go.mod: la capa de dependencias se reutiliza si no cambian.
COPY go.mod ./
RUN go mod download

COPY . .
# Binario estático (sin CGO) para que funcione en una imagen sin libc.
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" \
    -o /out/products-api ./cmd/products-api

# --- runtime -----------------------------------------------------------------
# distroless: sin shell ni gestor de paquetes, usuario no root. Menor superficie de ataque.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/products-api /products-api

ENV PORT=8080
EXPOSE 8080
USER nonroot:nonroot

# La imagen no tiene curl: el binario hace el healthcheck contra /health.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD ["/products-api", "-healthcheck"]

ENTRYPOINT ["/products-api"]
