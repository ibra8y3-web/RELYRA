FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/relyra-discovery ./cmd/relyra-discovery

FROM alpine:3.23
RUN apk add --no-cache ca-certificates \
    && addgroup -S relyra \
    && adduser -S -G relyra relyra \
    && mkdir -p /var/lib/relyra-discovery \
    && chown -R relyra:relyra /var/lib/relyra-discovery
COPY --from=build /out/relyra-discovery /usr/local/bin/relyra-discovery
USER relyra:relyra
EXPOSE 8080 4001/tcp
VOLUME ["/var/lib/relyra-discovery"]
ENTRYPOINT ["/usr/local/bin/relyra-discovery"]
