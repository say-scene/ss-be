# ss-be

## 관측성 연결

- `mvn compile`은 OpenTelemetry Java agent를 `target/otel-agent/opentelemetry-javaagent.jar`에 복사한다. Jib 이미지는 같은 JAR를 `/opt/otel/opentelemetry-javaagent.jar`에 포함한다.
- `/actuator/prometheus`는 Micrometer 메트릭을 노출한다. Kubernetes ServiceMonitor는 `ss-be` Service의 `http` 포트와 이 경로를 수집하도록 설정한다.
- 추적을 활성화할 때 BE 컨테이너에 `JAVA_TOOL_OPTIONS=-javaagent:/opt/otel/opentelemetry-javaagent.jar`, `OTEL_SERVICE_NAME=ss-be`, `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://ALLOY_OTLP_SERVICE:4318`을 설정한다. Alloy Service 이름은 배포 구성이 정해진 뒤 입력한다.
- 메트릭은 Prometheus가 직접 수집하고 로그는 Alloy가 Pod 로그에서 수집하므로, agent를 켤 때 `OTEL_METRICS_EXPORTER=none`과 `OTEL_LOGS_EXPORTER=none`을 함께 설정해 중복 전송을 막는다.
