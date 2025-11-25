# AIoT Vision Collector Forecast

[English](./README.md) | 简体中文

基于 Chronos-T5 模型的物联网时序数据预测 API 服务。

## 项目简介

本项目提供了一个轻量级的时序数据预测 REST API 服务，使用 Amazon 的 Chronos-T5-Tiny 预训练模型进行时间序列预测。适用于物联网设备数据采集后的趋势预测、异常检测等场景。

## 功能特性

- 🚀 基于预训练的 Chronos-T5-Tiny 模型，无需训练即可使用
- 📊 支持任意长度的历史时序数据输入
- 🔮 灵活的预测长度配置
- 🐳 完整的 Docker 容器化支持
- 🏥 内置健康检查机制
- 🔒 非 root 用户运行，安全性更高

## 技术栈

- **Python**: 3.13
- **Web 框架**: Flask 3.1.2
- **深度学习框架**: PyTorch 2.8.0
- **预测模型**: Chronos-Forecasting 1.5.3
- **其他依赖**: Transformers 4.56.2, NumPy 2.3.3

## 项目结构

```
aiot-vision-collector-forecast/
├── avc_forecast.py          # Flask API 服务主程序
├── requirements.txt         # Python 依赖清单
├── Dockerfile              # Docker 镜像构建文件
├── docker_build.sh         # Docker 构建脚本
├── docker_run.sh           # Docker 运行脚本
├── chronos-t5-tiny/        # 本地预训练模型目录
│   ├── config.json
│   ├── generation_config.json
│   ├── model.safetensors
│   └── README.md
└── README.md               # 项目说明文档
```

## 快速开始

### 方式一：直接运行（Python 环境）

#### 1. 安装依赖

```bash
pip install -r requirements.txt
```

#### 2. 启动服务

```bash
python avc_forecast.py
```

服务将在 `http://0.0.0.0:50000` 启动。

### 方式二：Docker 容器运行（推荐）

#### 1. 构建镜像

```bash
bash docker_build.sh
# 或直接运行
docker build -t avc-forecast:1.0.0-rc8 .
```

#### 2. 运行容器

```bash
bash docker_run.sh
# 或直接运行
docker run -d -p 50000:50000 --name avc-forecast avc-forecast:1.0.0-rc8
```

#### 3. 查看日志

```bash
docker logs -f avc-forecast
```

#### 4. 停止容器

```bash
docker stop avc-forecast
docker rm avc-forecast
```

## API 使用说明

### 预测接口

**端点**: `POST /predict`

**请求头**: `Content-Type: application/json`

**请求体**:

```json
{
  "data": [1.0, 2.0, 3.0, 4.0, 5.0],
  "prediction_length": 3
}
```

**参数说明**:

- `data` (必需): 历史时序数据数组，元素类型为浮点数或整数
- `prediction_length` (必需): 需要预测的未来时间点数量，必须为正整数

**响应示例**:

```json
{
  "predictions": [6.05, 7.12, 8.23]
}
```

**错误响应**:

```json
{
  "error": "Missing 'data' (list of doubles) or 'prediction_length' (int)"
}
```

### 使用示例

#### cURL

```bash
curl -X POST http://localhost:50000/predict \
  -H "Content-Type: application/json" \
  -d '{
    "data": [10.5, 12.3, 14.1, 15.8, 17.2, 19.0],
    "prediction_length": 5
  }'
```

#### Python

```python
import requests

url = "http://localhost:50000/predict"
payload = {
    "data": [10.5, 12.3, 14.1, 15.8, 17.2, 19.0],
    "prediction_length": 5
}

response = requests.post(url, json=payload)
print(response.json())
```

#### JavaScript (Node.js)

```javascript
const fetch = require('node-fetch');

const url = 'http://localhost:50000/predict';
const data = {
  data: [10.5, 12.3, 14.1, 15.8, 17.2, 19.0],
  prediction_length: 5
};

fetch(url, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(data)
})
  .then(res => res.json())
  .then(json => console.log(json));
```

## 环境变量

可在运行时通过环境变量配置：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `MODEL_PATH` | `chronos-t5-tiny` | 模型文件路径 |
| `PORT` | `50000` | 服务监听端口 |

Docker 运行示例：

```bash
docker run -d \
  -p 8080:8080 \
  -e PORT=8080 \
  --name avc-forecast \
  avc-forecast:1.0.0-rc8
```

## 健康检查

服务内置健康检查端点，每 30 秒自动检测一次：

```bash
curl -X POST http://localhost:50000/predict \
  -H "Content-Type: application/json" \
  -d '{"data":[0],"prediction_length":1}'
```

返回 200 状态码表示服务正常。

## 开发说明

### 模型说明

本项目使用 **Chronos-T5-Tiny** 模型，这是一个轻量级的时序预测模型：

- 模型大小：约 70MB
- 预训练数据：大规模时间序列数据集
- 支持零样本预测（无需微调）
- 输出中位数预测结果（50% 分位数）

### 自定义开发

如需扩展功能，可修改 `avc_forecast.py`：

1. **添加新端点**: 在 Flask app 中添加新的路由
2. **自定义预测参数**: 修改 `pipeline.predict()` 的参数
3. **更换模型**: 替换 `MODEL_PATH` 为其他 Chronos 模型（如 `chronos-t5-small`, `chronos-t5-base`）

## 常见问题

### 1. 模型加载失败

**问题**: `OSError: chronos-t5-tiny does not appear to be a valid repository`

**解决**: 确保 `chronos-t5-tiny/` 目录存在且包含所有必需文件（config.json、model.safetensors 等）。

### 2. 内存不足

**问题**: 容器运行时内存溢出

**解决**: 
- 使用更小的模型（已是 tiny 版本）
- 限制预测长度（建议不超过 64）
- 增加 Docker 容器内存限制：`docker run --memory=2g ...`

### 3. 预测结果不准确

**问题**: 预测值偏差较大

**解决**:
- 确保输入数据质量（无异常值、缺失值）
- 提供足够长的历史数据（建议至少 20 个数据点）
- 考虑数据归一化处理
- 根据业务场景选择更大的模型

## 性能优化

- **模型预加载**: 服务启动时加载模型到内存，避免每次请求重复加载
- **CPU 优化**: 默认使用 CPU 推理，适合轻量级部署
- **GPU 加速**: 如需 GPU 支持，修改 Dockerfile 基础镜像为 `pytorch/pytorch:*-cuda*`

## 许可证

本项目为内部开发项目，遵循公司相关许可协议。

Chronos 模型遵循 Apache 2.0 许可证。

## 联系方式

如有问题或建议，请联系项目维护团队：

- 项目仓库: `aiot-vision-labs/aiot-vision-collector-forecast`
- 问题反馈: 通过 Issue 跟踪系统提交

---

**版本**: 1.0.0-rc8  
**最后更新**: 2025-11-25

