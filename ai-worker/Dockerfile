FROM python:3.11-slim-bookworm

WORKDIR /app

ARG INSTALL_MODELS=true

RUN apt-get update -o Acquire::Retries=5 -o Acquire::http::Timeout=30 \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY requirements-models.txt .
RUN if [ "$INSTALL_MODELS" = "true" ]; then pip install --no-cache-dir -r requirements-models.txt; fi

COPY app.py .

EXPOSE 8090
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8090"]
