# 使用 openjdk:17-oracle 作为基础镜像
FROM eclipse-temurin:17-jre

# 设置容器内工作目录
WORKDIR /app

# 复制编译生成的 jar 包到镜像中
COPY target/*.jar /app/application.jar

# 创建  数据目录并设置权限
RUN mkdir -p /data && chmod -R 777 /data

# 暴露应用运行的端口
EXPOSE 8080

# 设置时区为上海时间
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# 设置 JVM 参数以禁用 Cgroup 检测（临时解决 Cgroup 问题）
ENV JAVA_OPTS="-XX:+DisableContainerSupport"


# 启动应用
CMD ["java", "-jar", "/app/application.jar"]