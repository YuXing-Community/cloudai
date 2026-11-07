FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build
COPY backend/pom.xml backend/.mvn-settings.xml ./
RUN mvn -B -s .mvn-settings.xml dependency:go-offline
COPY backend/src ./src
RUN mvn -B -s .mvn-settings.xml package

FROM eclipse-temurin:17-jre AS backend
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=backend-build /build/target/cloudai-backend-1.0.0.jar ./backend.jar
EXPOSE 8580
ENTRYPOINT ["java", "-jar", "backend.jar"]

FROM node:22-alpine AS frontend-build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM nginx:1.28-alpine AS frontend
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=frontend-build /build/dist /usr/share/nginx/html
EXPOSE 80

FROM python:3.12-slim AS executor
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
RUN sed -i 's|http://deb.debian.org|https://mirrors.aliyun.com|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends bash curl git libgomp1 \
    && rm -rf /var/lib/apt/lists/*
COPY executor/requirements.txt ./requirements.txt
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
RUN curl -fL 'https://download.pytorch.org/whl/cpu/torch-2.14.1%2Bcpu-cp312-cp312-manylinux_2_28_x86_64.whl' \
        -o '/tmp/torch-2.14.1+cpu-cp312-cp312-manylinux_2_28_x86_64.whl' \
    && pip install --no-cache-dir '/tmp/torch-2.14.1+cpu-cp312-cp312-manylinux_2_28_x86_64.whl' \
    && rm '/tmp/torch-2.14.1+cpu-cp312-cp312-manylinux_2_28_x86_64.whl'
COPY --from=ghcr.io/astral-sh/uv:0.8.22 /uv /usr/local/bin/uv
# The Milvus 2.4 SDK still imports pkg_resources.
RUN uv pip install --system --no-cache --index-url "$PIP_INDEX_URL" -r requirements.txt \
    'pymilvus>=2.4.4,<2.5' 'setuptools>=77.0.3,<81'
COPY executor/app ./app
EXPOSE 8590
CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8590"]
