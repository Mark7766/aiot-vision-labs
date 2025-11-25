# aiot-vision-labs

[English](./README.md) | 简体中文

本项目是一个面向 AI 与 IoT 融合场景的实验性探索平台。

## 🎯 项目使命

旨在构建一个灵活、可扩展的实验环境，用于快速验证从工业物联网数据采集、时序预测分析到最终部署交付的完整链路。我们致力于：

*   **降低创新成本**：为各类"采集+分析+决策"场景提供一个可快速搭建和测试的平台。
*   **沉淀核心能力**：将验证过的采集与推理模式固化为可复用的技术组件。
*   **明确职责边界**：通过分离核心逻辑与部署配置，为未来的扩展和维护提供清晰的架构。

## 📂 项目结构与实现

项目由三个核心子工程构成，分别承担"数据采集"、"时序预测"与"部署交付"的职责，实现关注点分离。

### [`aiot-vision-collector`](./aiot-vision-collector) - 采集与分析核心工程

一个用于采集工业/物联网设备实时点位数据、进行历史存储、展示及调用预测服务的轻量级 AI IoT 应用。

**核心功能**：
- **OPC UA 设备管理**：新增/修改/删除设备，支持命名空间与节点浏览
- **Tag 管理**：按设备维护采集点（支持快速添加、修改、删除）
- **实时快照**：展示每个设备最近一次采集时间、连接状态、各 Tag 最新值
- **历史查询**：单个 Tag 指定分钟窗口历史数据查询
- **预测接口**：聚合历史数据 + 调用外部预测 API 返回预测结果
- **预测缓存**：定时预取预测结果，提升查询性能
- **智能预警**：基于预测偏差的自动预警功能，支持活动预警列表、统计和确认/忽略操作
- **预警监控大屏**：简约风格的预警大屏展示页面（`/alerts/board`）
- **REST/JSON API + Web 可视化页面**
- **OpenAPI 文档**：集成 Swagger UI
- **环境变量配置**：可通过环境变量快速重写核心配置，便于容器化/云部署
- **Docker 支持**：提供 Dockerfile 和构建脚本

**技术栈**：
- Spring Boot 3.5.6
- Java 17
- Spring MVC, Thymeleaf
- Spring Data JPA, H2 Database (默认)
- IoTDB Session 客户端（可选）
- Eclipse Milo OPC UA 客户端
- springdoc-openapi (Swagger UI)
- Lombok, Maven

### [`aiot-vision-collector-forecast`](./aiot-vision-collector-forecast) - 时序预测服务

基于 Chronos-T5-Tiny 模型的轻量级时序预测服务，提供 HTTP REST API 接口供采集服务调用。

**核心功能**：
- 接收历史时序数据，返回未来时间点预测值
- 支持零样本预测（无需微调）
- 输出中位数预测结果（50% 分位数）
- 轻量级模型（约 70MB）
- 内置健康检查机制
- Docker 支持

**技术栈**：
- Python 3
- Flask 3.1.2
- PyTorch 2.8.0
- Transformers 4.56.2
- chronos-forecasting 1.5.3
- Chronos-T5-Tiny 模型

### [`aiot-vision-collector-deploy`](./aiot-vision-collector-deploy) - 部署与交付工程

负责应用的打包、配置和最终交付。它聚合 `aiot-vision-collector` 和 `aiot-vision-collector-forecast` 的构建产物，并通过 Docker Compose 编排所有服务（包括 IoTDB 时序数据库），形成一个可以独立运行的完整系统。

**核心功能**：
- Docker Compose 编排所有服务
- 离线部署支持（通过 `generate_tar.sh` 打包镜像）
- 环境变量配置管理
- 数据持久化卷管理

**包含服务**：
- avc-server (采集服务)
- avc-forecast (预测服务)
- iotdb (时序数据库)

## 🛠️ 技术栈汇总

### 后端服务
*   **核心语言与框架**: Java 17, Spring Boot 3.5.6
*   **构建与依赖管理**: Maven 3.9+
*   **数据持久化**: Spring Data JPA, H2 Database (默认)
*   **时序数据库**: Apache IoTDB 2.0.5 (可选)
*   **工业协议**: Eclipse Milo (OPC UA)
*   **API 文档**: springdoc-openapi (Swagger UI)

### AI 预测服务
*   **语言**: Python 3
*   **Web 框架**: Flask 3.1.2
*   **深度学习**: PyTorch 2.8.0, Transformers 4.56.2
*   **时序预测**: chronos-forecasting 1.5.3, Chronos-T5-Tiny 模型

### 前端技术
*   **模板引擎**: Thymeleaf
*   **脚本语言**: JavaScript (ES6+)
*   **样式**: CSS3

### 部署与运维
*   **容器化**: Docker, Docker Compose
*   **接口协议**: RESTful API (JSON)

## 🚀 快速开始

### 前置要求
- Docker 和 Docker Compose（推荐）
- 或：JDK 17+, Maven 3.9+, Python 3.x (本地开发)

### 使用 Docker Compose 部署（推荐）

1. 进入部署工程目录：
```bash
cd aiot-vision-collector-deploy
```

2. 启动所有服务：
```bash
docker compose up -d
```

3. 访问服务：
- Web 管理页面：http://localhost:8080/data
- OpenAPI 文档：http://localhost:8080/swagger-ui/index.html
- 预警监控大屏：http://localhost:8080/alerts/board
- 预测服务：http://localhost:50000/predict

4. 查看日志：
```bash
docker compose logs -f
```

### 离线部署

1. 在联网机器上打包镜像：
```bash
cd aiot-vision-collector-deploy
bash generate_tar.sh
```

2. 将生成的 `avc_images.tar` 拷贝到离线机器

3. 在离线机器上加载镜像并启动：
```bash
docker load -i avc_images.tar
docker compose up -d
```

详细部署说明请参考各子工程的 README 文档。

## 📖 使用指南

### 基本使用流程

1. 打开 http://localhost:8080/data 管理页面
2. 添加 OPC UA 设备（提供设备名称、协议类型和连接字符串）
3. 浏览设备的命名空间和节点，选择需要采集的数据点
4. 添加 Tag（数据采集点）
5. 查看实时数据快照和历史数据
6. 调用预测接口获取未来趋势预测
7. 访问 `/alerts/board` 查看预警监控大屏

### 核心 API 接口

**数据查询**：
- `GET /data/api/latest` - 获取实时快照
- `GET /data/api/history/{deviceId}/{tagId}` - 获取 Tag 历史
- `GET /data/api/predict/{deviceId}/{tagId}` - 获取预测结果

**设备管理**：
- `POST /data/api/devices` - 添加设备
- `PUT /data/api/devices/{deviceId}` - 更新设备
- `DELETE /data/api/devices/{deviceId}` - 删除设备

**Tag 管理**：
- `GET /data/api/{deviceId}/tags` - 列出设备 Tag
- `POST /data/api/{deviceId}/tags` - 添加 Tag
- `PUT /data/api/{deviceId}/tags/{tagId}` - 更新 Tag
- `DELETE /data/api/{deviceId}/tags/{tagId}` - 删除 Tag

**预警管理**：
- `GET /data/api/alerts` - 获取活动预警列表
- `GET /data/api/alerts/recent` - 获取最近预警列表
- `GET /data/api/alerts/stats` - 获取预警统计信息
- `POST /data/api/alerts/{alertId}/ack` - 确认预警
- `POST /data/api/alerts/{alertId}/ignore` - 忽略预警

**预测服务**：
- `POST /predict` - 时序数据预测接口

完整 API 文档请访问 Swagger UI：http://localhost:8080/swagger-ui/index.html

## 🔧 配置说明

### 环境变量配置

主要配置项（通过环境变量前缀 `AVC_` 覆盖）：

| 环境变量 | 描述 | 默认值 |
|---------|------|--------|
| `AVC_SERVER_PORT` | 应用端口 | 8080 |
| `AVC_IOTDB_HOST` | IoTDB 主机 | 127.0.0.1 |
| `AVC_IOTDB_PORT` | IoTDB 端口 | 6667 |
| `AVC_PREDICT_API_URL` | 预测服务 URL | http://localhost:50000/predict |
| `AVC_PREDICT_API_PREDICTION_LENGTH` | 预测点数 | 60 |
| `AVC_PREDICT_API_HISTORY_LENGTH` | 历史点数 | 300 |
| `AVC_PREDICT_CACHE_ENABLED` | 启用预测缓存 | true |
| `AVC_ALERT_ENABLED` | 启用预警功能 | true |
| `AVC_ALERT_DEVIATION_PERCENT_THRESHOLD` | 偏差百分比阈值 | 10 |

完整配置说明请参考 [aiot-vision-collector README](./aiot-vision-collector/README.md)

## 📊 系统架构

```
┌─────────────────┐      ┌──────────────────┐      ┌──────────────────┐
│   OPC UA 设备   │◄────►│  avc-server      │◄────►│  avc-forecast    │
│  (工业设备)     │      │  (采集分析服务)   │      │  (预测服务)      │
└─────────────────┘      └──────────────────┘      └──────────────────┘
                                  │                         │
                                  │                         │
                         ┌────────▼─────────┐      ┌────────▼─────────┐
                         │   H2 Database    │      │  Chronos Model   │
                         │   (元数据存储)    │      │  (时序预测模型)  │
                         └──────────────────┘      └──────────────────┘
                                  │
                         ┌────────▼─────────┐
                         │     IoTDB        │
                         │  (时序数据库)     │
                         └──────────────────┘
```

## 🤝 贡献指南

1. Fork 项目并创建新分支 `feature/xxx`
2. 编码并补充测试用例
3. 运行 `mvn test` 确保所有测试通过
4. 提交 Pull Request，描述变更与影响

## 📝 许可证

本项目为内部开发项目，遵循公司相关许可协议。

使用的开源组件遵循各自的许可证：
- Spring Boot: Apache License 2.0
- Eclipse Milo: Eclipse Public License 2.0
- Chronos: Apache License 2.0

## 📮 联系与反馈

如有问题或建议，请通过 Issue 跟踪系统提交。

---

**当前版本**: 
- aiot-vision-collector: 0.0.1-SNAPSHOT
- aiot-vision-collector-forecast: 1.0.0-rc8

**最后更新**: 2025-11-25

