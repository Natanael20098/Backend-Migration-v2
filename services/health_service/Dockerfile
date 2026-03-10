FROM python:3.11-slim

# System dependencies required by psycopg2
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libpq-dev \
        gcc \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install Poetry 2.x
RUN pip install --no-cache-dir "poetry>=2.0,<3.0"

# Copy dependency files first for layer caching
COPY pyproject.toml poetry.lock* ./

# Configure Poetry: no virtualenv inside container, install only main deps
# --no-root avoids trying to install the project package itself at this stage
RUN poetry config virtualenvs.create false \
    && poetry install --only main --no-interaction --no-ansi --no-root

# Copy the full source
COPY services/ services/

EXPOSE 8000

CMD ["python", "-m", "services.health_service.app"]
