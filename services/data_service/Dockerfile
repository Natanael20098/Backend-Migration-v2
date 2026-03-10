FROM python:3.11-slim

# System dependencies required by psycopg2
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libpq-dev \
        gcc \
        libffi-dev \
        libssl-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Poetry 2.x
RUN pip install --no-cache-dir "poetry>=2.0,<3.0"

# Copy dependency files first for layer caching
COPY pyproject.toml poetry.lock* ./

# Configure Poetry: no virtualenv inside container, install only main deps
RUN poetry config virtualenvs.create false \
    && poetry install --only main --no-interaction --no-ansi --no-root

# Copy the full source
COPY services/ services/

EXPOSE 8002

# Run with gunicorn for production-ready concurrency
CMD ["gunicorn", "--bind", "0.0.0.0:8002", "--workers", "2", "--threads", "4", \
     "--timeout", "60", "--access-logfile", "-", "--error-logfile", "-", \
     "services.data_service.app:app"]
