FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 10001 --create-home stt && mkdir /data && chown stt:stt /data
COPY app.py .
USER stt
EXPOSE 8765
CMD ["gunicorn", "--bind", "0.0.0.0:8765", "--workers", "1", "--threads", "4", "--timeout", "30", "--limit-request-line", "2048", "--limit-request-fields", "30", "app:create_app()"]
