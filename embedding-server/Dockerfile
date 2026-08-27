# 用本机已有镜像，避免再拉 python/apt
FROM pytorch/pytorch:2.3.0-cuda12.1-cudnn8-runtime

WORKDIR /app

ARG HF_ENDPOINT=https://hf-mirror.com
ENV HF_ENDPOINT=${HF_ENDPOINT}
ENV HF_HOME=/models/hf
ENV TRANSFORMERS_CACHE=/models/hf
ENV SENTENCE_TRANSFORMERS_HOME=/models/st
ENV MODEL_ID=IEITYuan/Yuan-embedding-2.0-zh
ENV MODEL_NAME=Yuan-embedding-2.0-zh
ENV DEVICE=cpu
ENV PIP_DISABLE_PIP_VERSION_CHECK=1
ENV PIP_NO_CACHE_DIR=1

COPY requirements.txt .
# 镜像已带 torch；其余用清华源
RUN pip install -i https://pypi.tuna.tsinghua.edu.cn/simple \
      fastapi==0.115.12 \
      "uvicorn[standard]==0.34.2" \
      sentence-transformers==3.4.1 \
      huggingface-hub==0.30.2

COPY app.py download_model.py entrypoint.sh ./
RUN chmod +x entrypoint.sh

EXPOSE 8008
HEALTHCHECK --interval=30s --timeout=10s --retries=20 --start-period=300s \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8008/health')"

ENTRYPOINT ["./entrypoint.sh"]
