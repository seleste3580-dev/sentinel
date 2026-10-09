# Build the Rust API as a small, self-contained release binary.
FROM rust:1-slim-bookworm AS builder
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --locked --release --bin sentinel-server

FROM debian:bookworm-slim AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /data \
    && chown 10001:10001 /data
WORKDIR /app
COPY --from=builder /src/target/release/sentinel-server /usr/local/bin/sentinel-server
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
ENV DATABASE_URL=sqlite:///data/sentinel.db?mode=rwc
VOLUME ["/data"]
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
CMD ["sentinel-server"]
