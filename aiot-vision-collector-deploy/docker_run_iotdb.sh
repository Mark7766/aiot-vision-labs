#!/usr/bin/env bash
# 快速单独启动 IoTDB (独立于 docker-compose)，用于测试或临时使用。
# 端口映射: 宿主机 6667 -> 容器 6667
# 如果后续通过 docker-compose 启动, 请避免端口冲突 (先 docker rm -f iotdb)。

docker run -d --name iotdb -p 6667:6667 apache/iotdb:2.0.5-standalone

# 查看日志: docker logs -f iotdb
# 进入容器: docker exec -it iotdb /bin/bash
