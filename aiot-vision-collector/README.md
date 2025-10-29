# AIoT Vision Collector

一个用于采集工业/物联网设备实时点位数据、进行历史存储、展示及调用预测服务的轻量级AI IOT 应用。

提供Web页面与JSON REST API，同时支持通过可插拔的预测服务（HTTP）获取未来时序预测结果。

---
## 功能特性
- OPC UA设备(Device)管理：新增 / 修改 / 删除
- Tag管理：按设备维护采集点（可快速添加）
- 实时快照：展示每个设备最近一次采集时间、连接状态、各Tag最新值
- 历史查询：单个Tag指定分钟窗口历史数据
- 预测接口：聚合历史数据 + 调用外部预测 API 返回预测结果
- OPC UA 名称空间与节点浏览
- REST/JSON API + Web 可视化页面
- OpenAPI 文档
- 可通过环境变量快速重写核心配置，便于容器化/云部署
- Docker 镜像构建脚本

---
## 技术栈
| 模块 | 技术 |
| ---- | ---- |
| 核心框架 | Spring Boot 3.5.6 |
| 语言 | Java 17 |
| Web & 模板 | Spring MVC, Thymeleaf |
| 持久化 | Spring Data JPA, H2 File DB (默认) |
| 时序/外部 | IoTDB Session 客户端 (可选) |
| 工业协议 | Eclipse Milo OPC UA 客户端 |
| 文档 | springdoc-openapi-starter-webmvc-ui |
| 其它 | Lombok, Maven |

---
## 目录结构概览
```
project/
  pom.xml
  Dockerfile
  docker_image_build.sh
  docker_container_start.sh
  src/
    main/
      java/com/sandy/aiot/vision/collector/... (业务代码)
      resources/
        application.yml (默认配置)
        templates/ (Thymeleaf 页面: data.html 等)
        static/ (前端静态资源 css/js)
    test/
      java/... (测试用例)
  data/ (默认 H2 文件数据库目录，运行时生成/持久化)
```

---
## 环境要求
- JDK 17+
- Maven 3.9+
- 外部预测服务 HTTP Endpoint (默认占位 URL)
- IoTDB 实例（若启用真实时序落库）
- Docker 环境（构建/运行容器）

---
## 快速开始 (本地)
1. 克隆源码
```
git clone <your-repo-url> aiot-vision-collector
cd aiot-vision-collector
```
2. 编译与运行
```
mvn clean package -DskipTests
java -jar target/aiot-vision-collector-0.0.1-SNAPSHOT.jar
```
   或开发模式：
```
mvn spring-boot:run
```
3. 访问
- Web 实时与管理页面: http://localhost:8080/data
- OpenAPI 文档（UI）：http://localhost:8080/swagger-ui/index.html

---
## 运行配置（application.yml 可被环境变量覆盖）
支持通过环境变量前缀 `AVC_` 修改，关键项如下（括号内为默认值）：

| 环境变量 | 描述 | 默认 |
| -------- | ---- | ---- |
| AVC_SERVER_PORT | 应用端口 | 8080 |
| AVC_DATASOURCE_URL | H2 JDBC URL | jdbc:h2:file:./data/visiondb;MODE=MySQL;DB_CLOSE_DELAY=-1 |
| AVC_DATASOURCE_DRIVER | 驱动类 | org.h2.Driver |
| AVC_DATASOURCE_USERNAME | 用户名 | sa |
| AVC_DATASOURCE_PASSWORD | 密码 | (空) |
| AVC_JPA_DATABASE_PLATFORM | Hibernate 方言 | org.hibernate.dialect.H2Dialect |
| AVC_JPA_HIBERNATE_DDL_AUTO | DDL 策略 | update |
| AVC_H2_CONSOLE_ENABLED | 启用 H2 控制台 | true |
| AVC_PREDICT_API_URL | 预测服务 URL | http://localhost:50000/predict |
| AVC_PREDICT_API_PREDICTION_LENGTH | 预测点数 | 60 |
| AVC_PREDICT_API_HISTORY_LENGTH | 发送给预测服务的历史点数 | 180 |
| AVC_DATA_API_HISTORY_LIMIT | REST 历史查询最大条数 | 200 |
| AVC_DATA_VIEW_LATEST_MINUTES_WINDOW | Web 快照窗口（分钟） | 5 |
| AVC_DATA_TAG_HISTORY_DEFAULT_MINUTES | Tag 历史页面默认分钟 | 3 |
| AVC_IOTDB_HOST | IoTDB 主机 | 127.0.0.1 |
| AVC_IOTDB_PORT | IoTDB 端口 | 6667 |
| AVC_IOTDB_USERNAME | IoTDB 用户 | root |
| AVC_IOTDB_PASSWORD | IoTDB 密码 | root |
| AVC_IOTDB_RT_DB | 实时库名（示例） | rt |
| AVC_IOTDB_RT_TTL | TTL 毫秒 | 86400000 |
| AVC_LOGGING_LEVEL_APP | 应用日志级别 | INFO |

示例（Windows CMD）：
```
set AVC_SERVER_PORT=9090
set AVC_PREDICT_API_URL=http://192.168.1.100:50000/predict
mvn spring-boot:run
```

---
## Docker 使用
1. 构建镜像 (脚本使用当前目录 Dockerfile)：
```
sh docker_image_build.sh
```
或手动：
```
docker build -t aiot-vision-collector:latest .
```
2. 运行容器（映射数据目录与端口）：
```
docker run -d --name avc \
  -p 8080:8080 \
  -e AVC_PREDICT_API_URL=http://host.docker.internal:50000/predict \
  -v %CD%/data:/app/data \
  aiot-vision-collector:latest
```
(Windows PowerShell 将 %CD% 换为 ${PWD})

3. 查看日志：
```
docker logs -f avc
```

---
## 核心使用流程
1. 打开 http://localhost:8080/data 初始可能无设备
2. 通过页面或 API 添加设备提供 name、protocol(opcua)、connectionString (OPC UA 连接串 如: opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer)
3. 浏览 Namespace（调用 `/data/api/{deviceId}/namespaces` 和 `/data/api/{deviceId}/namespaces/{nsIndex}/tags`）选择需要的节点地址
4. 快速添加 Tag（地址即 OPC UA 节点 ID）
5. 页面将周期刷新显示最新值；预测功能会对已存储历史点进行组合调用外部预测服务

---
## 主要 REST API 列表（节选）
所有响应为 JSON（除重定向与 HTML 页面）。示例 curl：

- 获取实时快照：
```
curl http://localhost:8080/data/api/latest
```
- 获取 Tag 历史：
```
curl http://localhost:8080/data/api/history/1/10
```
- 预测：
```
curl http://localhost:8080/data/api/predict/1/10
```
- 查询命名空间：
```
curl http://localhost:8080/data/api/1/namespaces
```
- 查询命名空间 Tag：
```
curl http://localhost:8080/data/api/1/namespaces/0/tags
```
- 添加设备：
```
curl -X POST http://localhost:8080/data/api/devices \
  -H "Content-Type: application/json" \
  -d '{"name":"DeviceA","protocol":"opcua","connectionString":"opc.tcp://127.0.0.1:4840"}'
```
- 更新设备：
```
curl -X PUT http://localhost:8080/data/api/devices/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"DeviceA2","protocol":"opcua","connectionString":"opc.tcp://127.0.0.1:4840"}'
```
- 删除设备：
```
curl -X DELETE http://localhost:8080/data/api/devices/1
```
- 列出某设备 Tag：
```
curl http://localhost:8080/data/api/1/tags
```
- 快速添加 Tag：
```
curl -X POST http://localhost:8080/data/api/1/tags \
  -H "Content-Type: application/json" \
  -d '{"name":"Temp","address":"ns=2;i=10845"}'
```
- 更新 Tag：
```
curl -X PUT http://localhost:8080/data/api/1/tags/10 \
  -H "Content-Type: application/json" \
  -d '{"name":"Temp2","address":"ns=2;i=10845"}'
```
- 删除 Tag：
```
curl -X DELETE http://localhost:8080/data/api/1/tags/10
```

更多字段说明请访问 OpenAPI UI。

---
## 预测服务对接说明
应用不会自行训练模型，而是将一段历史数据（长度由 `AVC_PREDICT_API_HISTORY_LENGTH` 控制）发送到 `AVC_PREDICT_API_URL`，期望返回预测序列（长度 `AVC_PREDICT_API_PREDICTION_LENGTH`）。若预测失败或异常，接口返回空结构（TimeSeriesDataModelRsp.empty()）。
预测服务工程地址:https://github.com/Mark7766/python-machine-learning-examples/tree/main/iot_forecast_api
集成建议：
- 确保预测服务可用并按约定返回 JSON
- 使用健康探测脚本定期检测预测端
- 在容器中通过环境变量指向预测服务（host.docker.internal 等）

---
## 测试
运行全部测试：
```
mvn test
```
生成覆盖率（可自行集成 Jacoco）：
```
mvn clean test
```

---
## 常见问题 & 排错 (Troubleshooting)
1. 启动端口被占用
   - 现象：`Web server failed to start` / `Address already in use`
   - 处理：修改 `AVC_SERVER_PORT` 或释放端口。
2. H2 数据库锁 / 启动失败
   - 现象：`Database ... is already in use`。
   - 处理：确认无旧进程占用；或删除 `./data/visiondb.*`（注意先备份）。
3. 预测接口返回空数据
   - 现象：预测 JSON 为空数组。
   - 处理：检查预测服务 URL、网络连通性和服务日志；查看应用日志中 `Prediction failed` 相关错误。
4. OPC UA 无法连接
   - 现象：设备 connectionOk=false。
   - 处理：验证 OPC UA 端点可达、证书策略（当前示例可能使用默认信任策略），确认 connectionString 正确。
5. Lombok 注解未生效（IDE 报错）
   - 处理：安装 Lombok 插件并启用 Annotation Processing。
6. Docker 映射数据未持久化
   - 处理：确保 `-v <host>/data:/app/data`（镜像中工作目录若不同需与 Dockerfile 保持一致），检查容器内写权限。
7. 时序写入/IoTDB 相关异常
   - 处理：确认 IoTDB 服务在线、用户名密码正确；必要时降低批量写频率或增加日志级别 DEBUG 检查细节。
8. OpenAPI 页面 404
   - 处理：确认依赖版本；访问 `/swagger-ui/index.html` 而非旧版路径；查看日志中是否有加载错误。
9. Maven 构建失败 (依赖下载慢)
   - 处理：配置国内镜像仓库（如阿里云）或启用本地代理。
10. Windows 路径编码问题
    - 处理：确认系统默认编码 UTF-8；必要时在 JVM 启动参数加 `-Dfile.encoding=UTF-8`。

收集日志：
```
# 临时提升日志等级
set AVC_LOGGING_LEVEL_APP=DEBUG
mvn spring-boot:run
```

---
## 安全建议
- 生产环境禁用 H2 Console：`AVC_H2_CONSOLE_ENABLED=false`
- 使用外部数据库或时序库替代默认 H2 文件
- 对管理与写入接口增加认证（可集成 Spring Security）
- 日志避免打印敏感字段（如密码）

---
## 性能与扩展
- 对高频数据：建议切换至 IoTDB/TimescaleDB 等，抽象 DataStorageService 实现
- 添加缓存：可在读取最新值时引入 Caffeine/Redis
- 预测调用：可异步化 + 结果缓存
- 水平扩展：外置数据库 + 共享缓存，前置负载均衡

---
## 未来可拓展方向 (Backlog 建议)
- 多协议接入 (Modbus, MQTT)
- 异步采集任务调度 / 批量写入缓冲
- 告警规则引擎
- 用户/权限管理
- Grafana/Prometheus 集成
- WebSocket 实时推送

---
## 发布与版本
POM 当前版本：0.0.1-SNAPSHOT。发布时建议：
1. 更新变更日志 CHANGELOG.md
2. 使用 Git Tag（例如 v0.1.0）
3. 上传构建产物 / Docker 镜像

---
## 许可证
可根据业务需要选择合适的开源许可证（MIT/Apache-2.0/GPLv3 等）。示例：
```
Copyright (c) <Year> <Owner>
```

---
## 贡献指南 (简要)
1. Fork & 新建分支 feature/xxx
2. 编码并补充测试
3. 通过 `mvn -q -DskipTests=false test` 确认通过
4. 提交 PR，描述变更与影响

---
## 联系与反馈
- 问题单：Issue Tracker
- 功能建议：提交 Feature Request
- 日志/复现：附加 `DEBUG` 日志与配置截屏

祝您使用顺利！

---
## 预警监控大屏 (Alerts Board)

新增页面: `/alerts/board`

设计原则 (Apple 简约风):
- 信息聚焦: 只保留核心指标 (活动预警数、24h新增、严重级别分布、近12小时趋势)。
- 视觉克制: 同一配色体系下的少量强调色 (Accent / Danger / Warn / OK)。
- 层次清晰: 栅格化卡片 + 轻量阴影，不使用复杂装饰。
- 动态刷新: 前端每 10 秒自动拉取最新统计与活动预警列表，页面隐藏时暂停刷新以降低资源消耗。

主要 REST 接口:
- `GET /data/api/alerts` 活动预警列表
- `GET /data/api/alerts/stats` 统计指标 (活动数量、24h新增、严重级别分布、近12小时趋势)
- `POST /data/api/alerts/{id}/ack` 确认预警
- `POST /data/api/alerts/{id}/ignore` 忽略预警

统计结构示例:
```json
{
  "activeCount": 3,
  "recent24hCount": 15,
  "severityActive": {"HIGH":1, "MEDIUM":1, "LOW":1},
  "severityRecent24h": {"HIGH":4, "MEDIUM":6, "LOW":5},
  "hourStats": [ {"hour":"08:00","count":2}, ... ]
}
```

前端文件: `src/main/resources/static/js/alert-board.js`
样式扩展: `app.css` 中新增 `.board-*` / `.severity-chip` / `.alert-item-row` 等选择器。

可扩展点:
- 增加筛选 (按设备 / 严重级别) 与分页
- 添加 WebSocket 推送替代轮询
- 国际化 (message / severity 标签)

测试:
- `AlertStatsApiTest` 验证统计接口结构 (小时桶固定为 12)。
