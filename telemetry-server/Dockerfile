FROM python:3.13-slim
WORKDIR /service
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir .
EXPOSE 8000
