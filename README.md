# aiot-vision-labs

English | [简体中文](./README_zh.md)

An experimental platform for AI and IoT convergence scenarios.

## 🎯 Mission

To build a flexible and scalable experimental environment for rapid verification of the complete workflow from industrial IoT data collection, time-series prediction analysis to final deployment delivery. We are committed to:

*   **Reducing Innovation Costs**: Providing a quickly deployable and testable platform for various "collection + analysis + decision" scenarios.
*   **Accumulating Core Capabilities**: Solidifying verified collection and inference patterns into reusable technical components.
*   **Defining Clear Boundaries**: Providing a clear architecture for future expansion and maintenance by separating core logic from deployment configuration.

## 📂 Project Structure

The project consists of three core subprojects, each responsible for "data collection", "time-series prediction" and "deployment delivery", achieving separation of concerns.

### [`aiot-vision-collector`](./aiot-vision-collector) - Data Collection & Analysis Core

A lightweight AI IoT application for collecting real-time point data from industrial/IoT devices, performing historical storage, display, and invoking prediction services.

**Core Features**:
- **OPC UA Device Management**: Add/modify/delete devices, support namespace and node browsing
- **Tag Management**: Maintain collection points by device (support quick add, modify, delete)
- **Real-time Snapshot**: Display the latest collection time, connection status, and latest values for each device tag
- **Historical Query**: Query historical data for a single tag within a specified minute window
- **Prediction Interface**: Aggregate historical data + invoke external prediction API to return prediction results
- **Prediction Cache**: Periodically prefetch prediction results to improve query performance
- **Smart Alerts**: Automatic alert function based on prediction deviation, support active alert list, statistics and confirm/ignore operations
- **Alert Monitoring Dashboard**: Minimalist style alert dashboard page (`/alerts/board`)
- **REST/JSON API + Web Visualization Pages**
- **OpenAPI Documentation**: Integrated Swagger UI
- **Environment Variable Configuration**: Quickly override core configurations via environment variables for containerization/cloud deployment
- **Docker Support**: Provides Dockerfile and build scripts

**Tech Stack**:
- Spring Boot 3.5.6
- Java 17
- Spring MVC, Thymeleaf
- Spring Data JPA, H2 Database (default)
- IoTDB Session Client (optional)
- Eclipse Milo OPC UA Client
- springdoc-openapi (Swagger UI)
- Lombok, Maven

### [`aiot-vision-collector-forecast`](./aiot-vision-collector-forecast) - Time-Series Prediction Service

A lightweight time-series prediction service based on the Chronos-T5-Tiny model, providing HTTP REST API interface for collection service invocation.

**Core Features**:
- Receive historical time-series data and return future time point predictions
- Support zero-shot prediction (no fine-tuning required)
- Output median prediction results (50% quantile)
- Lightweight model (approximately 70MB)
- Built-in health check mechanism
- Docker support

**Tech Stack**:
- Python 3
- Flask 3.1.2
- PyTorch 2.8.0
- Transformers 4.56.2
- chronos-forecasting 1.5.3
- Chronos-T5-Tiny Model

### [`aiot-vision-collector-deploy`](./aiot-vision-collector-deploy) - Deployment & Delivery

Responsible for application packaging, configuration and final delivery. It aggregates the build artifacts of `aiot-vision-collector` and `aiot-vision-collector-forecast`, and orchestrates all services (including IoTDB time-series database) through Docker Compose to form a complete system that can run independently.

**Core Features**:
- Docker Compose orchestration for all services
- Offline deployment support (package images via `generate_tar.sh`)
- Environment variable configuration management
- Data persistence volume management

**Included Services**:
- avc-server (collection service)
- avc-forecast (prediction service)
- iotdb (time-series database)

## 🛠️ Technology Stack

### Backend Services
*   **Core Language & Framework**: Java 17, Spring Boot 3.5.6
*   **Build & Dependency Management**: Maven 3.9+
*   **Data Persistence**: Spring Data JPA, H2 Database (default)
*   **Time-Series Database**: Apache IoTDB 2.0.5 (optional)
*   **Industrial Protocol**: Eclipse Milo (OPC UA)
*   **API Documentation**: springdoc-openapi (Swagger UI)

### AI Prediction Service
*   **Language**: Python 3
*   **Web Framework**: Flask 3.1.2
*   **Deep Learning**: PyTorch 2.8.0, Transformers 4.56.2
*   **Time-Series Prediction**: chronos-forecasting 1.5.3, Chronos-T5-Tiny Model

### Frontend
*   **Template Engine**: Thymeleaf
*   **Scripting Language**: JavaScript (ES6+)
*   **Styling**: CSS3

### Deployment & Operations
*   **Containerization**: Docker, Docker Compose
*   **Interface Protocol**: RESTful API (JSON)

## 🚀 Quick Start

### Prerequisites
- Docker and Docker Compose (recommended)
- Or: JDK 17+, Maven 3.9+, Python 3.x (for local development)

### Deploy with Docker Compose (Recommended)

1. Navigate to the deployment directory:
```bash
cd aiot-vision-collector-deploy
```

2. Start all services:
```bash
docker compose up -d
```

3. Access services:
- Web Management Page: http://localhost:8080/data
- OpenAPI Documentation: http://localhost:8080/swagger-ui/index.html
- Alert Monitoring Dashboard: http://localhost:8080/alerts/board
- Prediction Service: http://localhost:50000/predict

4. View logs:
```bash
docker compose logs -f
```

### Offline Deployment

1. Package images on an online machine:
```bash
cd aiot-vision-collector-deploy
bash generate_tar.sh
```

2. Copy the generated `avc_images.tar` to the offline machine

3. Load images and start on the offline machine:
```bash
docker load -i avc_images.tar
docker compose up -d
```

For detailed deployment instructions, please refer to the README documentation of each subproject.

## 📖 User Guide

### Basic Usage Flow

1. Open http://localhost:8080/data management page
2. Add OPC UA device (provide device name, protocol type and connection string)
3. Browse device namespaces and nodes, select data points to collect
4. Add Tags (data collection points)
5. View real-time data snapshots and historical data
6. Call prediction interface to get future trend predictions
7. Visit `/alerts/board` to view alert monitoring dashboard

### Core API Endpoints

**Data Query**:
- `GET /data/api/latest` - Get real-time snapshot
- `GET /data/api/history/{deviceId}/{tagId}` - Get tag history
- `GET /data/api/predict/{deviceId}/{tagId}` - Get prediction results

**Device Management**:
- `POST /data/api/devices` - Add device
- `PUT /data/api/devices/{deviceId}` - Update device
- `DELETE /data/api/devices/{deviceId}` - Delete device

**Tag Management**:
- `GET /data/api/{deviceId}/tags` - List device tags
- `POST /data/api/{deviceId}/tags` - Add tag
- `PUT /data/api/{deviceId}/tags/{tagId}` - Update tag
- `DELETE /data/api/{deviceId}/tags/{tagId}` - Delete tag

**Alert Management**:
- `GET /data/api/alerts` - Get active alerts list
- `GET /data/api/alerts/recent` - Get recent alerts list
- `GET /data/api/alerts/stats` - Get alert statistics
- `POST /data/api/alerts/{alertId}/ack` - Acknowledge alert
- `POST /data/api/alerts/{alertId}/ignore` - Ignore alert

**Prediction Service**:
- `POST /predict` - Time-series data prediction interface

For complete API documentation, visit Swagger UI: http://localhost:8080/swagger-ui/index.html

## 🔧 Configuration

### Environment Variables

Main configuration items (override via `AVC_` prefix):

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `AVC_SERVER_PORT` | Application port | 8080 |
| `AVC_IOTDB_HOST` | IoTDB host | 127.0.0.1 |
| `AVC_IOTDB_PORT` | IoTDB port | 6667 |
| `AVC_PREDICT_API_URL` | Prediction service URL | http://localhost:50000/predict |
| `AVC_PREDICT_API_PREDICTION_LENGTH` | Number of prediction points | 60 |
| `AVC_PREDICT_API_HISTORY_LENGTH` | Number of history points | 300 |
| `AVC_PREDICT_CACHE_ENABLED` | Enable prediction cache | true |
| `AVC_ALERT_ENABLED` | Enable alert feature | true |
| `AVC_ALERT_DEVIATION_PERCENT_THRESHOLD` | Deviation percentage threshold | 10 |

For complete configuration details, please refer to [aiot-vision-collector README](./aiot-vision-collector/README.md)

## 📊 System Architecture

```
┌─────────────────┐      ┌──────────────────┐      ┌──────────────────┐
│   OPC UA Device │◄────►│  avc-server      │◄────►│  avc-forecast    │
│  (Industrial)   │      │  (Collection)     │      │  (Prediction)    │
└─────────────────┘      └──────────────────┘      └──────────────────┘
                                  │                         │
                                  │                         │
                         ┌────────▼─────────┐      ┌────────▼─────────┐
                         │   H2 Database    │      │  Chronos Model   │
                         │   (Metadata)     │      │  (Time-Series)   │
                         └──────────────────┘      └──────────────────┘
                                  │
                         ┌────────▼─────────┐
                         │     IoTDB        │
                         │  (Time-Series DB)│
                         └──────────────────┘
```

## 🤝 Contributing

1. Fork the project and create a new branch `feature/xxx`
2. Code and add test cases
3. Run `mvn test` to ensure all tests pass
4. Submit Pull Request with description of changes and impact

## 📝 License

This project is an internal development project and follows the company's related license agreements.

Open source components used follow their respective licenses:
- Spring Boot: Apache License 2.0
- Eclipse Milo: Eclipse Public License 2.0
- Chronos: Apache License 2.0

## 📮 Contact & Feedback

For issues or suggestions, please submit through the Issue tracking system.

---

**Current Version**: 
- aiot-vision-collector: 0.0.1-SNAPSHOT
- aiot-vision-collector-forecast: 1.0.0-rc8

**Last Updated**: 2025-11-25

