FROM node:24-alpine3.24 AS runtime
COPY server/prepare-ocr.mjs /tmp/prepare-ocr.mjs
RUN node /tmp/prepare-ocr.mjs /ocr-v1 && apk add --no-cache binutils && strip --strip-unneeded /usr/local/bin/node

FROM alpine:3.24
COPY --from=runtime /usr/local/bin/node /usr/local/bin/node
COPY --from=runtime /usr/lib/libstdc++.so* /usr/lib/
COPY --from=runtime /usr/lib/libgcc_s.so.1 /usr/lib/
COPY --from=runtime /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
WORKDIR /app
COPY server/ server/
COPY web/ web/
COPY --from=runtime /ocr-v1/ web/ocr-v1/
RUN addgroup -g 1000 -S moroshka && adduser -u 1000 -S -G moroshka moroshka && \
    chmod -R a+rX /app && mkdir /data && chown moroshka:moroshka /data
USER moroshka
ENV HOST=0.0.0.0 PORT=8090 VAULT_DIR=/data
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=4s --start-period=5s CMD node -e "fetch('http://127.0.0.1:8090/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node","server/app.mjs"]
