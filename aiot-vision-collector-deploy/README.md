# AIoT Vision Collector Deploy

English | [简体中文](./README_zh.md)

Deployment and delivery project for AIoT Vision Collector.

## Overview

This project is responsible for packaging, configuration, and final delivery of the application. It aggregates the build artifacts of `aiot-vision-collector` and `aiot-vision-collector-forecast`, and orchestrates all services (including IoTDB time-series database) through Docker Compose to form a complete system that can run independently.

## Services Included

- **avc-server**: Data collection and analysis service
- **avc-forecast**: Time-series prediction service
- **iotdb**: Time-series database

## Environment Variables

- `AVC_IOTDB_HOST`: Points to the IoTDB service hostname or IP. Default uses compose service name `iotdb`.
- `AVC_PREDICT_API_URL`: Prediction service endpoint address. Default: `http://avc-forecast:50000/predict`.

If you change IoTDB to host network (`network_mode: host`) or deploy to an external server, you need to change `AVC_IOTDB_HOST` to the actual IP/domain.

## Offline Deployment Process

1. Prepare images on an online machine with access to image sources (pull or build).
2. Run packaging script:
   ```bash
   bash generate_tar.sh
   ```
3. Copy the generated `avc_images.tar` to the target offline machine.
4. Load images on offline machine:
   ```bash
   docker load -i avc_images.tar
   docker images | grep avc
   ```
5. Execute compose in offline machine directory:
   ```bash
   docker compose up -d
   ```

## Standalone IoTDB Testing

If you only need to temporarily start IoTDB:
```bash
bash docker_run_iotdb.sh
```
Then use `docker rm -f iotdb` to stop, to avoid conflicts with compose.

## Health Verification

- Access Collector endpoint: `http://<host>:8080/` (specific endpoint depends on service implementation, access `/health` if available)
- Test prediction: `curl http://<host>:50000/predict` (needs to adjust request body according to actual requirements)
- Verify IoTDB port connectivity: `telnet <host> 6667` or `nc -vz <host> 6667` (Linux)

## FAQ

1. **Cannot connect to IoTDB**: Confirm `AVC_IOTDB_HOST` is correct, inter-container DNS is normal (`docker exec avc-server ping iotdb`).
2. **Port occupation**: Modify `ports` mapping in `docker-compose.yml`.
3. **Permission issues (volume cannot write)**: Grant write permissions to host directory: `chmod -R 0775 /data/avc`.
4. **Offline environment missing base images (eclipse-temurin / python)**: Ensure base images are included in `generate_tar.sh` and successfully loaded.

## Version Upgrade Guide

- Update image tags (e.g., `1.0.0-rc8`): Edit `docker-compose.yml` and `generate_tar.sh` to keep consistent.
- Re-package offline images.
- Rolling restart: `docker compose pull && docker compose up -d` (for offline, use `load` then `up -d`).

## Security and Operations Recommendations

- Use private registry and enable image signing/scanning.
- Add resource limits for production environment:
  ```yaml
  deploy:
    resources:
      limits:
        cpus: '2'
        memory: 2g
  ```
- Regularly backup `/app/data` and IoTDB data directory (can mount to specified host path).

## Future Extensibility

- Add health checks (Docker `healthcheck`).
- Use `.env` file for centralized version and variable management.
- Add monitoring (Prometheus + Grafana).

For further script automation or new features, please submit requirements.

---

**Version**: 1.0.0-rc8  
**Last Updated**: 2025-11-25

