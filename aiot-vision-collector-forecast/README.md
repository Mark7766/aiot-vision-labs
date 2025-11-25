# AIoT Vision Collector Forecast

English | [简体中文](./README_zh.md)

IoT time-series data prediction API service based on Chronos-T5 model.

## Project Overview

This project provides a lightweight time-series data prediction REST API service using Amazon's Chronos-T5-Tiny pre-trained model for time series forecasting. Suitable for trend prediction, anomaly detection and other scenarios after IoT device data collection.

## Features

- 🚀 Based on pre-trained Chronos-T5-Tiny model, ready to use without training
- 📊 Supports arbitrary length historical time-series data input
- 🔮 Flexible prediction length configuration
- 🐳 Complete Docker containerization support
- 🏥 Built-in health check mechanism
- 🔒 Runs as non-root user for enhanced security

## Tech Stack

- **Python**: 3.13
- **Web Framework**: Flask 3.1.2
- **Deep Learning Framework**: PyTorch 2.8.0
- **Prediction Model**: Chronos-Forecasting 1.5.3
- **Other Dependencies**: Transformers 4.56.2, NumPy 2.3.3

## Project Structure

```
aiot-vision-collector-forecast/
├── avc_forecast.py          # Flask API service main program
├── requirements.txt         # Python dependencies
├── Dockerfile              # Docker image build file
├── docker_build.sh         # Docker build script
├── docker_run.sh           # Docker run script
├── chronos-t5-tiny/        # Local pre-trained model directory
│   ├── config.json
│   ├── generation_config.json
│   ├── model.safetensors
│   └── README.md
└── README.md               # Project documentation
```

## Quick Start

### Method 1: Direct Run (Python Environment)

#### 1. Install Dependencies

```bash
pip install -r requirements.txt
```

#### 2. Start Service

```bash
python avc_forecast.py
```

Service will start at `http://0.0.0.0:50000`.

### Method 2: Docker Container Run (Recommended)

#### 1. Build Image

```bash
bash docker_build.sh
# Or directly run
docker build -t avc-forecast:1.0.0-rc8 .
```

#### 2. Run Container

```bash
bash docker_run.sh
# Or directly run
docker run -d -p 50000:50000 --name avc-forecast avc-forecast:1.0.0-rc8
```

#### 3. View Logs

```bash
docker logs -f avc-forecast
```

#### 4. Stop Container

```bash
docker stop avc-forecast
docker rm avc-forecast
```

## API Usage

### Prediction Endpoint

**Endpoint**: `POST /predict`

**Request Header**: `Content-Type: application/json`

**Request Body**:

```json
{
  "data": [1.0, 2.0, 3.0, 4.0, 5.0],
  "prediction_length": 3
}
```

**Parameters**:

- `data` (required): Historical time-series data array, elements can be floats or integers
- `prediction_length` (required): Number of future time points to predict, must be a positive integer

**Response Example**:

```json
{
  "predictions": [6.05, 7.12, 8.23]
}
```

**Error Response**:

```json
{
  "error": "Missing 'data' (list of doubles) or 'prediction_length' (int)"
}
```

### Usage Examples

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

## Environment Variables

Can be configured via environment variables at runtime:

| Variable | Default Value | Description |
|----------|---------------|-------------|
| `MODEL_PATH` | `chronos-t5-tiny` | Model file path |
| `PORT` | `50000` | Service listening port |

Docker run example:

```bash
docker run -d \
  -p 8080:8080 \
  -e PORT=8080 \
  --name avc-forecast \
  avc-forecast:1.0.0-rc8
```

## Health Check

Service has built-in health check endpoint, auto-detected every 30 seconds:

```bash
curl -X POST http://localhost:50000/predict \
  -H "Content-Type: application/json" \
  -d '{"data":[0],"prediction_length":1}'
```

Returning 200 status code indicates the service is normal.

## Development Notes

### Model Description

This project uses the **Chronos-T5-Tiny** model, a lightweight time-series prediction model:

- Model size: approximately 70MB
- Pre-training data: Large-scale time series datasets
- Supports zero-shot prediction (no fine-tuning required)
- Outputs median prediction results (50% quantile)

### Custom Development

To extend functionality, modify `avc_forecast.py`:

1. **Add new endpoints**: Add new routes in Flask app
2. **Customize prediction parameters**: Modify `pipeline.predict()` parameters
3. **Replace model**: Replace `MODEL_PATH` with other Chronos models (e.g., `chronos-t5-small`, `chronos-t5-base`)

## FAQ

### 1. Model Loading Failure

**Issue**: `OSError: chronos-t5-tiny does not appear to be a valid repository`

**Solution**: Ensure `chronos-t5-tiny/` directory exists and contains all required files (config.json, model.safetensors, etc.).

### 2. Out of Memory

**Issue**: Container runs out of memory

**Solution**: 
- Use smaller model (already using tiny version)
- Limit prediction length (recommend not exceeding 64)
- Increase Docker container memory limit: `docker run --memory=2g ...`

### 3. Inaccurate Predictions

**Issue**: Large prediction value deviation

**Solution**:
- Ensure input data quality (no outliers, missing values)
- Provide sufficient historical data (recommend at least 20 data points)
- Consider data normalization
- Choose larger model based on business scenario

## Performance Optimization

- **Model Pre-loading**: Load model to memory at service startup, avoid repeated loading per request
- **CPU Optimization**: Default uses CPU inference, suitable for lightweight deployment
- **GPU Acceleration**: For GPU support, modify Dockerfile base image to `pytorch/pytorch:*-cuda*`

## License

This project is an internal development project and follows the company's related license agreements.

Chronos model follows Apache 2.0 license.

## Contact

For issues or suggestions, please contact the project maintenance team:

- Project Repository: `aiot-vision-labs/aiot-vision-collector-forecast`
- Issue Feedback: Submit through Issue tracking system

---

**Version**: 1.0.0-rc8  
**Last Updated**: 2025-11-25

