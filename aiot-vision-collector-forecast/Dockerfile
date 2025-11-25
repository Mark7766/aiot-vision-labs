# 基于官方轻量级 Python 运行时（CPU）
FROM python:3.13-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MODEL_PATH=chronos-t5-tiny \
    PORT=50000

# 安装基础系统依赖（如果后续某些包需要可在此添加）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 创建工作目录
WORKDIR /app

# 先复制依赖声明，加快缓存利用
COPY requirements.txt ./

# 安装 Python 依赖
RUN pip install --upgrade pip \
    && pip install --no-cache-dir -r requirements.txt

# 复制应用代码及模型目录
# 若后续新增文件，确保在构建上下文中
COPY avc_forecast.py ./
# （可选）复制其他本地 python 脚本，方便后续扩展
COPY *.py ./

# 复制本地模型（保持目录结构）
COPY chronos-t5-tiny ./chronos-t5-tiny

# 创建并切换到非 root 用户
RUN useradd -m appuser \
    && chown -R appuser:appuser /app
USER appuser

EXPOSE ${PORT}

# 健康检查（简单检查端口和进程）
# 可以在部署平台额外定义更完善的探针
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://127.0.0.1:${PORT}/predict -H 'Content-Type: application/json' \
      -d '{"data":[0],"prediction_length":1}' || exit 1

# 启动命令
CMD ["python", "avc_forecast.py"]

