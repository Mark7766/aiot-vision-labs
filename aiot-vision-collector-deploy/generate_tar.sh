#!/usr/bin/env bash
# 用途: 导出部署所需镜像到单个 tar 以便离线环境传输与加载。
# 运行前请确保这些镜像已在本机存在 (docker images 查看)。
# 如需增加版本, 修改下面列表。保持与 docker-compose.yml 一致。

docker save -o avc_images.tar \
  avc-server:1.0.0-rc7 \
  avc-forecast:1.0.0-rc7 \
  apache/iotdb:2.0.5-standalone \
  eclipse-temurin:17-jre \
  python:3.13-slim

# 使用方式:
#   bash generate_tar.sh
# 离线机器加载:
#   docker load -i avc_images.tar
