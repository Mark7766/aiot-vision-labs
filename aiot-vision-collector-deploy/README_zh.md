# AIoT Vision Collector Deploy

[English](./README.md) | 简体中文

AIoT Vision Collector 部署与交付工程。

## 概述

本项目负责应用的打包、配置和最终交付。它聚合 `aiot-vision-collector` 和 `aiot-vision-collector-forecast` 的构建产物，并通过 Docker Compose 编排所有服务（包括 IoTDB 时序数据库），形成一个可以独立运行的完整系统。

## 包含服务

- **avc-server**: 数据采集与分析服务
- **avc-forecast**: 时序预测服务
- **iotdb**: 时序数据库

## 环境变量说明

- `AVC_IOTDB_HOST`: 指向 IoTDB 服务主机名或 IP。默认使用 compose 服务名 `iotdb`。
- `AVC_PREDICT_API_URL`: 预测服务接口地址。默认：`http://avc-forecast:50000/predict`。

如果你将 IoTDB 改为 host 网络 (`network_mode: host`) 或部署在外部服务器，需要把 `AVC_IOTDB_HOST` 改成实际 IP/域名。

## 离线部署流程

1. 在一台可访问镜像源的在线机器上准备镜像（拉取或构建）。
2. 运行脚本打包:
   ```bash
   bash generate_tar.sh
   ```
3. 将生成的 `avc_images.tar` 拷贝到目标离线机器。
4. 离线机器加载镜像:
   ```bash
   docker load -i avc_images.tar
   docker images | grep avc
   ```
5. 在离线机器目录中执行 compose:
   ```bash
   docker compose up -d
   ```

## 单独测试 IoTDB

若只需临时启动 IoTDB：
```bash
bash docker_run_iotdb.sh
```
之后使用 `docker rm -f iotdb` 停止，以免与 compose 冲突。

## 健康验证

- 访问 Collector 接口: `http://<host>:8080/` (具体接口根据服务实现，若有 `/health` 可访问)
- 测试预测: `curl http://<host>:50000/predict` (需根据实际请求体调整)
- 验证 IoTDB 端口连通: `telnet <host> 6667` 或 `nc -vz <host> 6667` (Linux)

## 常见问题

1. **无法连接 IoTDB**: 确认 `AVC_IOTDB_HOST` 是否正确，容器间 DNS 正常 (`docker exec avc-server ping iotdb`).
2. **端口占用**: 更改 `docker-compose.yml` 中 `ports` 映射。
3. **权限问题 (卷无法写入)**: 赋予宿主机目录写权限: `chmod -R 0775 /data/avc`。
4. **离线环境缺少基础镜像 (eclipse-temurin / python)**: 确保 `generate_tar.sh` 中基础镜像已包含并成功加载。

## 版本升级指南

- 更新镜像标签 (如 `1.0.0-rc8`)：编辑 `docker-compose.yml` 与 `generate_tar.sh` 保持一致。
- 重新打包离线镜像。
- 滚动重启: `docker compose pull && docker compose up -d` (离线则用 `load` 后再 `up -d`).

## 安全与运维建议

- 使用私有 registry 并开启镜像签名/扫描。
- 为生产环境添加资源限制:
  ```yaml
  deploy:
    resources:
      limits:
        cpus: '2'
        memory: 2g
  ```
- 定期备份 `/app/data` 与 IoTDB 数据目录 (可挂载到宿主机指定路径）。

## 后续可扩展方向

- 添加健康检查（Docker `healthcheck`）。
- 使用 `.env` 文件集中管理版本与变量。
- 增加监控 (Prometheus + Grafana)。

如需进一步脚本自动化或新增功能，请提出需求。

---

**版本**: 1.0.0-rc8  
**最后更新**: 2025-11-25

