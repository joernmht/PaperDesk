# PaperDesk agent sandbox: one container per chat, runs as uid 1000, no secrets inside.
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends git make ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 PYTHONDONTWRITEBYTECODE=1 HOME=/workspace/.home
WORKDIR /workspace
CMD ["sleep", "infinity"]
