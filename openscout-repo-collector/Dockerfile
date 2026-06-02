# Build stage
FROM golang:1.23-alpine AS build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /collector ./cmd/server

# Runtime stage
FROM alpine:3.19
RUN addgroup -S openscout && adduser -S openscout -G openscout
COPY --from=build /collector /usr/local/bin/collector
USER openscout
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
  CMD wget -qO- http://localhost:8081/health | grep -q UP
ENTRYPOINT ["/usr/local/bin/collector"]
