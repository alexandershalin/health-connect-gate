# Health Connect Gate receiver. The program uses only the Python standard library, so nothing is installed with pip.
# Build:  docker build -t health-connect-gate-receiver .        (run from this directory)
# See the repository README ("Docker") and compose.yaml for a complete, hardened setup.
ARG PYTHON_VERSION=3.13
FROM python:${PYTHON_VERSION}-slim

# 0.0.0.0 is the container's own interface: publish the port on 127.0.0.1 only (compose.yaml does) and put a TLS
# reverse proxy in front. Without HEALTH_RECEIVER_AUTH_URL every request is refused (fail closed).
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    HEALTH_RECEIVER_HOST=0.0.0.0 \
    HEALTH_RECEIVER_PORT=9120 \
    HEALTH_RECEIVER_ROOT=/data \
    HEALTH_RECEIVER_LOCK=/run/receiver/receiver.lock

RUN groupadd --system --gid 10001 gate \
 && useradd --system --uid 10001 --gid gate --no-create-home --shell /usr/sbin/nologin gate \
 && mkdir -p /app /data /run/receiver \
 && chown gate:gate /data /run/receiver \
 && chmod 700 /data /run/receiver

WORKDIR /app
COPY receiver.py health_sqlite.py ./

USER gate
VOLUME /data
EXPOSE 9120

# /healthz only says the process answers; /readyz also checks the auth backend and is for your own monitoring.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD ["python3", "-c", "import os,urllib.request as u; u.build_opener(u.ProxyHandler({})).open('http://127.0.0.1:%s/healthz' % os.environ['HEALTH_RECEIVER_PORT'], timeout=3)"]

# Arguments are passed on: `docker run ... IMAGE backup`, `... verify`.
ENTRYPOINT ["python3", "/app/receiver.py"]
