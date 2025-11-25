# AIoT Vision Collector

English | [简体中文](./README_zh.md)

A lightweight AI IoT application for collecting real-time point data from industrial/IoT devices, performing historical storage, display, and invoking prediction services.

Provides Web pages and JSON REST API, supports obtaining future time-series prediction results through pluggable prediction service (HTTP).

---
## Features
- **OPC UA Device Management**: Add / modify / delete devices
- **Tag Management**: Maintain collection points by device (support quick add, modify, delete)
- **Real-time Snapshot**: Display the latest collection time, connection status, and latest values for each device tag
- **Historical Query**: Query historical data for a single tag within a specified minute window
- **Prediction Interface**: Aggregate historical data + invoke external prediction API to return prediction results
- **Prediction Cache**: Periodically prefetch prediction results to improve query performance
- **Smart Alerts**: Automatic alert function based on prediction deviation, support active alert list, statistics and confirm/ignore operations
- **Alert Monitoring Dashboard**: Minimalist style alert dashboard page (/alerts/board)
- **OPC UA Namespace & Node Browsing**: Support browsing device namespaces and nodes
- **REST/JSON API + Web Visualization Pages**
- **OpenAPI Documentation**: Integrated Swagger UI
- **Environment Variable Configuration**: Quickly override core configurations via environment variables for containerization/cloud deployment
- **Docker Support**: Provides Dockerfile and build scripts

---
## Tech Stack
| Module | Technology |
| ------ | ---------- |
| Core Framework | Spring Boot 3.5.6 |
| Language | Java 17 |
| Web & Template | Spring MVC, Thymeleaf |
| Persistence | Spring Data JPA, H2 File DB (default) |
| Time-Series/External | IoTDB Session Client (optional) |
| Industrial Protocol | Eclipse Milo OPC UA Client |
| Documentation | springdoc-openapi-starter-webmvc-ui |
| Others | Lombok, Maven |

---
## Directory Structure Overview
```
project/
  pom.xml
  Dockerfile
  docker_image_build.sh
  docker_container_start.sh
  src/
    main/
      java/com/sandy/aiot/vision/collector/... (business code)
      resources/
        application.yml (default configuration)
        templates/ (Thymeleaf pages: data.html, etc.)
        static/ (frontend static resources css/js)
    test/
      java/... (test cases)
  data/ (default H2 file database directory, generated/persisted at runtime)
```

---
## Environment Requirements
- JDK 17+
- Maven 3.9+
- External prediction service HTTP Endpoint (default placeholder URL)
- IoTDB instance (if enabling real time-series storage)
- Docker environment (build/run containers)

---
## Quick Start (Local)
1. Clone source code
```bash
git clone <your-repo-url> aiot-vision-collector
cd aiot-vision-collector
```
2. Compile and run
```bash
mvn clean package -DskipTests
java -jar target/aiot-vision-collector-0.0.1-SNAPSHOT.jar
```
   Or development mode:
```bash
mvn spring-boot:run
```
3. Access
- Web real-time and management page: http://localhost:8080/data
- OpenAPI documentation (UI): http://localhost:8080/swagger-ui/index.html

---
## Runtime Configuration (application.yml can be overridden by environment variables)
Supports modification via environment variable prefix `AVC_`, key items are as follows (default values in parentheses):

| Environment Variable | Description | Default |
| -------------------- | ----------- | ------- |
| AVC_SERVER_PORT | Application port | 8080 |
| AVC_DATASOURCE_URL | H2 JDBC URL | jdbc:h2:file:./data/visiondb;MODE=MySQL;DB_CLOSE_DELAY=-1 |
| AVC_DATASOURCE_DRIVER | Driver class | org.h2.Driver |
| AVC_DATASOURCE_USERNAME | Username | sa |
| AVC_DATASOURCE_PASSWORD | Password | (empty) |
| AVC_JPA_DATABASE_PLATFORM | Hibernate dialect | org.hibernate.dialect.H2Dialect |
| AVC_JPA_HIBERNATE_DDL_AUTO | DDL strategy | update |
| AVC_H2_CONSOLE_ENABLED | Enable H2 console | true |
| AVC_PREDICT_API_URL | Prediction service URL | http://localhost:50000/predict |
| AVC_PREDICT_API_PREDICTION_LENGTH | Number of prediction points | 60 |
| AVC_PREDICT_API_HISTORY_LENGTH | Number of history points sent to prediction service | 300 |
| AVC_PREDICT_CACHE_ENABLED | Enable prediction cache | true |
| AVC_PREDICT_CACHE_PREFETCH_INTERVAL_MS | Prediction cache prefetch interval (milliseconds) | 30000 |
| AVC_PREDICT_CACHE_MIN_AHEAD_MINUTES | Prediction cache minimum ahead minutes | 2 |
| AVC_PREDICT_CACHE_TOLERANCE_MS | Prediction cache time matching tolerance (milliseconds) | 30000 |
| AVC_PREDICT_CACHE_MAX_POINTS_PER_TAG | Maximum cache points per tag | 5000 |
| AVC_DATA_API_HISTORY_LIMIT | REST history query max records | 200 |
| AVC_DATA_VIEW_LATEST_MINUTES_WINDOW | Web snapshot window (minutes) | 5 |
| AVC_DATA_TAG_HISTORY_DEFAULT_MINUTES | Tag history page default minutes | 3 |
| AVC_ALERT_ENABLED | Enable alert feature | true |
| AVC_ALERT_SCAN_INTERVAL_MS | Alert scan interval (milliseconds) | 60000 |
| AVC_ALERT_DUP_SUPPRESS_MINUTES | Duplicate alert suppression time (minutes) | 5 |
| AVC_ALERT_PREDICTION_ENABLED | Enable prediction-based deviation alerts | true |
| AVC_ALERT_DEVIATION_PERCENT_THRESHOLD | Deviation percentage threshold | 10 |
| AVC_IOTDB_HOST | IoTDB host | 127.0.0.1 |
| AVC_IOTDB_PORT | IoTDB port | 6667 |
| AVC_IOTDB_USERNAME | IoTDB username | root |
| AVC_IOTDB_PASSWORD | IoTDB password | root |
| AVC_IOTDB_RT_DB | Real-time database name (example) | rt |
| AVC_IOTDB_RT_TTL | TTL milliseconds | 86400000 |
| AVC_LOGGING_LEVEL_APP | Application log level | INFO |

Example (Windows PowerShell):
```powershell
$env:AVC_SERVER_PORT=9090
$env:AVC_PREDICT_API_URL="http://192.168.1.100:50000/predict"
mvn spring-boot:run
```

---
## Docker Usage
1. Build image (script uses current directory Dockerfile):
```bash
sh docker_image_build.sh
```
Or manually:
```bash
mvn clean package -Dmaven.test.skip=true
docker build -t avc-server:1.0.0-rc9 .
```
2. Run container (map data directory and port):
```bash
docker run -d --name avc \
  -p 8080:8080 \
  -e AVC_IOTDB_HOST=192.168.1.4 \
  -e AVC_PREDICT_API_URL=http://192.168.1.4:50000/predict \
  -v $(pwd)/data:/data \
  avc-server:1.0.0-rc9
```

3. View logs:
```bash
docker logs -f avc
```

---
## Core Usage Flow
1. Open http://localhost:8080/data, initially may have no devices
2. Add device via page or API, provide name, protocol(opcua), connectionString (OPC UA connection string, e.g.: opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer)
3. Browse Namespace (call `/data/api/{deviceId}/namespaces` and `/data/api/{deviceId}/namespaces/{nsIndex}/tags`) to select required node addresses
4. Quick add Tag (address is OPC UA node ID)
5. Page will periodically refresh to display latest values; prediction feature will combine stored historical points to call external prediction service
6. Visit `/alerts/board` to view alert monitoring dashboard and understand system alert status

---
## Main REST API List (Selected)
All responses are JSON (except redirects and HTML pages). Example curl:

**Data Query Endpoints**:
- Get real-time snapshot:
```bash
curl http://localhost:8080/data/api/latest
```
- Get tag history:
```bash
curl http://localhost:8080/data/api/history/{deviceId}/{tagId}
```
- Prediction:
```bash
curl http://localhost:8080/data/api/predict/{deviceId}/{tagId}
```

**Device Management Endpoints**:
- Add device:
```bash
curl -X POST http://localhost:8080/data/api/devices \
  -H "Content-Type: application/json" \
  -d '{"name":"DeviceA","protocol":"opcua","connectionString":"opc.tcp://127.0.0.1:4840"}'
```
- Update device:
```bash
curl -X PUT http://localhost:8080/data/api/devices/{deviceId} \
  -H "Content-Type: application/json" \
  -d '{"name":"DeviceA2","protocol":"opcua","connectionString":"opc.tcp://127.0.0.1:4840"}'
```
- Delete device:
```bash
curl -X DELETE http://localhost:8080/data/api/devices/{deviceId}
```

**Tag Management Endpoints**:
- List device tags:
```bash
curl http://localhost:8080/data/api/{deviceId}/tags
```
- Quick add tag:
```bash
curl -X POST http://localhost:8080/data/api/{deviceId}/tags \
  -H "Content-Type: application/json" \
  -d '{"name":"Temp","address":"ns=2;i=10845"}'
```
- Update tag:
```bash
curl -X PUT http://localhost:8080/data/api/{deviceId}/tags/{tagId} \
  -H "Content-Type: application/json" \
  -d '{"name":"Temp2","address":"ns=2;i=10845"}'
```
- Delete tag:
```bash
curl -X DELETE http://localhost:8080/data/api/{deviceId}/tags/{tagId}
```

**OPC UA Browsing Endpoints**:
- Query namespaces:
```bash
curl http://localhost:8080/data/api/{deviceId}/namespaces
```
- Query namespace tags:
```bash
curl http://localhost:8080/data/api/{deviceId}/namespaces/{nsIndex}/tags
```

**Alert Endpoints**:
- Get active alerts list:
```bash
curl http://localhost:8080/data/api/alerts
```
- Get recent alerts list:
```bash
curl http://localhost:8080/data/api/alerts/recent
```
- Get alert statistics:
```bash
curl http://localhost:8080/data/api/alerts/stats
```
- Acknowledge alert:
```bash
curl -X POST http://localhost:8080/data/api/alerts/{alertId}/ack
```
- Ignore alert:
```bash
curl -X POST http://localhost:8080/data/api/alerts/{alertId}/ignore
```

For more field descriptions, please visit OpenAPI UI.

---
## Prediction Service Integration Instructions
The application does not train models itself, but sends a segment of historical data (length controlled by `AVC_PREDICT_API_HISTORY_LENGTH`) to `AVC_PREDICT_API_URL`, expecting a prediction sequence (length `AVC_PREDICT_API_PREDICTION_LENGTH`) in return. If prediction fails or errors, the interface returns an empty structure (TimeSeriesDataModelRsp.empty()).

**Prediction Cache Mechanism**:
- System periodically prefetches prediction results through `PredictionCacheService`, avoiding real-time calls to prediction service on every query
- Cache configuration parameters:
  - `AVC_PREDICT_CACHE_ENABLED`: Enable/disable prediction cache (default true)
  - `AVC_PREDICT_CACHE_PREFETCH_INTERVAL_MS`: Prefetch interval (default 30 seconds)
  - `AVC_PREDICT_CACHE_MIN_AHEAD_MINUTES`: Minimum ahead minutes for prediction coverage (default 2 minutes)
  - `AVC_PREDICT_CACHE_TOLERANCE_MS`: Time matching tolerance (default 30 seconds)
  - `AVC_PREDICT_CACHE_MAX_POINTS_PER_TAG`: Maximum cache points per tag (default 5000)

[Prediction Service Project](../aiot-vision-collector-forecast)

Integration suggestions:
- Ensure prediction service is available and returns JSON as agreed
- Use health probe scripts to periodically detect prediction endpoint
- Point to prediction service via environment variables in container (e.g., 192.168.1.4:50000)

---
## Testing
Run all tests:
```bash
mvn test
```
Generate coverage (can integrate Jacoco):
```bash
mvn clean test
```

---
## FAQ & Troubleshooting
1. Startup port occupied
   - Symptom: `Web server failed to start` / `Address already in use`
   - Solution: Modify `AVC_SERVER_PORT` or release port.
2. H2 database lock / startup failure
   - Symptom: `Database ... is already in use`.
   - Solution: Confirm no old process occupying; or delete `./data/visiondb.*` (backup first).
3. Prediction interface returns empty data
   - Symptom: Prediction JSON is empty array.
   - Solution: Check prediction service URL, network connectivity and service logs; view application logs for `Prediction failed` related errors.
4. OPC UA connection failure
   - Symptom: Device connectionOk=false.
   - Solution: Verify OPC UA endpoint reachable, certificate policy (current example may use default trust policy), confirm connectionString correct.
5. Lombok annotations not working (IDE errors)
   - Solution: Install Lombok plugin and enable Annotation Processing.
6. Docker mapped data not persisted
   - Solution: Ensure `-v <host>/data:/data` (map to container's /data directory), check write permissions in container.
7. Time-series write/IoTDB related exceptions
   - Solution: Confirm IoTDB service online, username password correct; reduce batch write frequency if necessary or increase log level DEBUG to check details.
8. OpenAPI page 404
   - Solution: Confirm dependency version; access `/swagger-ui/index.html` instead of old path; check logs for loading errors.
9. Maven build failure (slow dependency download)
   - Solution: Configure domestic mirror repository (e.g., Alibaba Cloud) or enable local proxy.
10. Windows path encoding issues
    - Solution: Confirm system default encoding UTF-8; add `-Dfile.encoding=UTF-8` to JVM startup parameters if necessary.

Collect logs (Windows PowerShell):
```powershell
$env:AVC_LOGGING_LEVEL_APP="DEBUG"
mvn spring-boot:run
```

---
## Security Recommendations
- Disable H2 Console in production: `AVC_H2_CONSOLE_ENABLED=false`
- Use external database or time-series database to replace default H2 file
- Add authentication to management and write interfaces (can integrate Spring Security)
- Avoid printing sensitive fields in logs (e.g., passwords)

---
## Performance & Scalability
- For high-frequency data: recommend switching to IoTDB/TimescaleDB, etc., abstract DataStorageService implementation
- Add caching: can introduce Caffeine/Redis when reading latest values
- Prediction calls: can be asynchronous + result caching
- Horizontal scaling: external database + shared cache, front-end load balancing

---
## Release & Version
POM current version: 0.0.1-SNAPSHOT. Recommended for release:
1. Update changelog CHANGELOG.md
2. Use Git Tag (e.g., v0.1.0)
3. Upload build artifacts / Docker images

---
## License
You can choose an appropriate open source license according to business needs (MIT/Apache-2.0/GPLv3, etc.). Example:
```
Copyright (c) <Year> <Owner>
```

---
## Contributing Guidelines (Brief)
1. Fork & create branch feature/xxx
2. Code and add tests
3. Confirm passing via `mvn test`
4. Submit PR, describe changes and impact

---
## Contact & Feedback
- Issues: Issue Tracker
- Feature requests: Submit Feature Request
- Logs/reproduction: Attach `DEBUG` logs and configuration screenshots

Wish you smooth usage!

---
## Alert Monitoring Dashboard (Alerts Board)

Access page: `/alerts/board`

Design principles (minimalist style):
- **Information Focus**: Only retain core metrics (active alerts, 24h new, severity distribution, recent 12-hour trend)
- **Visual Restraint**: Limited accent colors under unified color scheme (Accent / Danger / Warn / OK)
- **Clear Hierarchy**: Grid-based cards + lightweight shadows, no complex decorations
- **Dynamic Refresh**: Frontend automatically fetches latest statistics and active alert list every 10 seconds, pauses refresh when page is hidden to reduce resource consumption

Main REST endpoints:
- `GET /data/api/alerts` - Active alerts list
- `GET /data/api/alerts/recent` - Recent alerts list
- `GET /data/api/alerts/stats` - Statistics metrics (active count, 24h new, severity distribution, recent 12-hour trend)
- `POST /data/api/alerts/{id}/ack` - Acknowledge alert
- `POST /data/api/alerts/{id}/ignore` - Ignore alert

Statistics structure example:
```json
{
  "activeCount": 3,
  "recent24hCount": 15,
  "severityActive": {"HIGH":1, "MEDIUM":1, "LOW":1},
  "severityRecent24h": {"HIGH":4, "MEDIUM":6, "LOW":5},
  "hourStats": [
    {"hour":"08:00","count":2},
    {"hour":"09:00","count":3}
  ]
}
```

Frontend files:
- `src/main/resources/static/js/alerts-enhanced.js` - Alert dashboard interaction logic
- `src/main/resources/templates/alerts-board.html` - Alert dashboard page template
- `src/main/resources/static/css/app.css` - Style definitions (`.board-*` / `.severity-chip` / `.alert-item-row`, etc.)

Testing:
- `AlertStatsApiTest` validates statistics interface structure (hour buckets fixed at 12)
- `AlertBoardViewControllerTest` validates page access

