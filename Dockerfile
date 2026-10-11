# IM 应用镜像：直接打包已构建的可执行 jar（前置：在仓库根执行 `mvn -q -DskipTests package`）。
# 说明：chat-nexus 框架为本地私有依赖（非公共仓库），故镜像不在容器内重新编译源码，改为复用宿主构建产物；
# 生产 CI 中亦采用"构建 jar -> 打包镜像"两步，保持一致。
FROM eclipse-temurin:17-jre
WORKDIR /app
ARG JAR_FILE=im-app/target/im-app-1.0.0-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar

RUN useradd -r -u 1001 appuser && mkdir -p /home/appuser && chown -R appuser:appuser /app /home/appuser
USER appuser

# 8180=REST/Actuator；9100=WebSocket(Netty)
EXPOSE 8180 9100
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
