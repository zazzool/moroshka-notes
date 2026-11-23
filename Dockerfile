FROM node:24-alpine
WORKDIR /app
COPY server/ server/
COPY web/ web/
RUN chmod -R a+rX /app && mkdir /data && chown node:node /data
USER node
ENV HOST=0.0.0.0 PORT=8090 VAULT_DIR=/data
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=4s --start-period=5s CMD node -e "fetch('http://127.0.0.1:8090/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node","server/app.mjs"]
