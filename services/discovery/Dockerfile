FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/relyra-discovery ./cmd/relyra-discovery

FROM gcr.io/distroless/static-debian13:nonroot
COPY --from=build /out/relyra-discovery /usr/local/bin/relyra-discovery
USER nonroot:nonroot
EXPOSE 8080 4001/tcp
VOLUME ["/var/lib/relyra-discovery"]
ENTRYPOINT ["/usr/local/bin/relyra-discovery"]
