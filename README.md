# ss-be

## 관측성 연결

- `mvn compile`은 OpenTelemetry Java agent를 `target/otel-agent/opentelemetry-javaagent.jar`에 복사한다. Jib 이미지는 같은 JAR를 `/opt/otel/opentelemetry-javaagent.jar`에 포함한다.
- `/actuator/prometheus`는 Micrometer 메트릭을 노출한다. Kubernetes ServiceMonitor는 `ss-be` Service의 `http` 포트와 이 경로를 수집하도록 설정한다.
- 추적을 활성화할 때 BE 컨테이너에 `JAVA_TOOL_OPTIONS=-javaagent:/opt/otel/opentelemetry-javaagent.jar`, `OTEL_SERVICE_NAME=ss-be`, `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://ALLOY_OTLP_SERVICE:4318`을 설정한다. Alloy Service 이름은 배포 구성이 정해진 뒤 입력한다.
- 메트릭은 Prometheus가 직접 수집하고 로그는 Alloy가 Pod 로그에서 수집하므로, agent를 켤 때 `OTEL_METRICS_EXPORTER=none`과 `OTEL_LOGS_EXPORTER=none`을 함께 설정해 중복 전송을 막는다.

### 브라우저 동작과 API 연결

- FE의 `speech.turn`은 녹음 종료 후 STT 요청부터 대화 응답 처리까지 한 trace로 연결한다. 개별 API 요청은 서로 다른 CLIENT span이며, `traceparent`를 받은 BE Java agent의 SERVER span이 그 아래에 이어진다. 번역·추천 등 별도 동작은 별도 trace다.
- FE가 생성하는 `session.id`는 **페이지 메모리에만 존재하는 임의 UUID**다. 같은 페이지의 여러 trace를 찾는 용도이며, 새로고침·새 페이지에서는 바뀐다. 사용자 계정, 실제 대화 `sessionId`, 재시도용 `requestId`와 무관하고 브라우저 저장소에 저장하지 않는다.
- 브라우저 span은 `POST /api/telemetry/traces`로 OTLP/JSON을 전송한다. BE는 크기·요청량·동시 처리량을 제한하고 허용된 이름과 속성만 재구성해 고정된 수집기로 전달한다. `BROWSER_TELEMETRY_OTLP_ENDPOINT=http://ALLOY_OTLP_SERVICE:4318/v1/traces`를 설정해야 하며, 비어 있으면 relay는 비활성화된다. 이 주소는 브라우저에 노출하거나 클라이언트 입력으로 받지 않는다.
- 비어 있지 않은 수집 주소가 잘못되면 endpoint는 `503`을 반환하고 고정된 설정 경고만 한 번 기록한다. 수집 주소나 요청 데이터는 로그에 기록하지 않는다.
- 기존 `/api` 라우팅을 사용하므로 Collector를 인터넷에 공개하거나 CORS를 확대할 필요가 없다. 수집 endpoint는 사용자 인증 endpoint가 아니며, 요청량 제한은 BE 인스턴스 단위다. 공개 배포의 추가 방어가 필요하면 앞단의 WAF/rate limit을 별도로 적용한다.
- 발화 내용·오디오·요청/응답 body·쿠키·인증 헤더·실제 대화 식별자·예외 메시지는 FE span에 기록하지 않는다. 추론 서버 내부 병목은 기존 메트릭으로 관측한다.
- 수집은 best effort다. 초기화·전송 실패는 업무 API 요청을 막지 않으며, 연결이 끊기면 FE span이 유실될 수 있다. FE CLIENT span은 fetch 응답 헤더 수신까지, 동작 span은 이어지는 응답 처리까지 측정하며 음성 재생 완료 시간을 의미하지 않는다.

Tempo에서 동작을 찾은 뒤 FE span의 `session.id`로 같은 페이지의 다른 API 흐름을 조회한다. 이 속성은 FE span에만 있고, 결과 trace에는 연결된 BE span도 포함된다.

```traceql
{ resource.service.name = "ss-fe" && span:name = "speech.turn" }
{ resource.service.name = "ss-fe" && span.session.id = "관측용-UUID" }
```

FE·BE 이미지와 ConfigMap 변경은 함께 배포해야 한다. 로컬 검증만으로 실제 CloudFront/ALB 라우팅과 운영 수집 성공을 보장하지는 않는다.
