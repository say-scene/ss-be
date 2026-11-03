# ss-be

SayScene의 Spring Boot API 서버다. 브라우저의 영어 회화 요청을 받아 **Redis 대화 상태**, **로컬 GPU의 vLLM**, **CPU Whisper STT**를 연결한다. 음성 합성은 브라우저가 담당하며, 서버는 완성된 JSON 응답을 반환한다.

## 구성

Java 21 · Spring Boot 3.5.7 · Maven · Spring Data Redis · Micrometer · OpenTelemetry Java agent.

| 파일 | 역할 |
|---|---|
| `ConversationController.java`, `ConversationStore.java` | 대화 시작·복원·발화·수정·종료, Redis 상태·버전 관리 |
| `OpeningLines.java`, `src/main/resources/prompts/` | 상황별 첫 인사와 LLM 프롬프트 |
| `InferenceController.java` | OpenAI 호환 LLM 호출, 추천·번역·단어 조회·리포트 |
| `SttController.java` | multipart 오디오를 KServe v2 형식 STT 요청으로 변환 |
| `BrowserTelemetryController.java` | 브라우저 trace 검증·정제·비공개 수집기 전달 |
| `src/main/resources/application.yml`, `.env.example` | 실행 설정과 환경변수 예시 |
| `.github/workflows/ecr-push.yml` | Jib 이미지 빌드·ECR push·ss-k8s 이미지 태그 갱신 |

Java 파일은 `src/main/java/com/sayscene/`에 있다.

## 로컬 실행

Java 21과 Maven이 필요하다. Redis(`127.0.0.1:6379`), OpenAI 호환 LLM(`localhost:8000`), [CPU STT 서버](https://github.com/say-scene/ss-triton)를 먼저 준비한다. 아래 모델 이름은 연결한 LLM의 served model 이름과 맞춘다.

```bash
export INFERENCE_BASE_URL=http://localhost:8000
export INFERENCE_MODEL=qwen3-4b-fp8
export STT_BASE_URL=http://127.0.0.1:8001
export SESSION_COOKIE_SECURE=false
mvn spring-boot:run
```

기본 API 주소는 `http://localhost:8080`이다. FE 개발 서버는 `/api`를 이 주소로 프록시한다. `.env.example`은 참고 파일이며 **`.env`로 복사하는 것만으로 자동 적용되지 않는다.** 셸 또는 실행 도구에서 환경변수를 전달한다. `SESSION_COOKIE_SECURE=false`는 localhost HTTP 개발용이며 HTTPS 배포에서는 기본값 `true`를 유지한다.

```bash
mvn test
mvn package
```

추론 연결만 확인하려면 별도 터미널에서 `python3 scripts/mock_inference.py`를 실행하고 BE의 `INFERENCE_MODEL=mock-cafe`, `INFERENCE_MODE=mock`을 설정한다. 이 서버는 고정 대화 응답용이며 추천·번역·리포트 전체 기능을 대신하지 않는다. `INFERENCE_MODE=mock` 자체가 모의 서버를 생성하지는 않는다.

### 주요 환경변수

| 변수 | 코드 기본값 | 용도 |
|---|---|---|
| `PORT` | `8080` | API 포트 |
| `INFERENCE_BASE_URL` | `http://localhost:8000` | OpenAI 호환 주소. `/v1` 앞까지만 지정 |
| `INFERENCE_MODEL` | 빈 값 | served model 이름. 비어 있으면 추론 요청 실패 |
| `INFERENCE_API_KEY` | 빈 값 | 필요할 때 Bearer 인증 |
| `INFERENCE_MODE` / `INFERENCE_TIMEOUT_SECONDS` | `live` / `45` | 응답 모드 표식 / LLM 제한 시간(초) |
| `STT_BASE_URL` / `STT_TIMEOUT_SECONDS` | `http://127.0.0.1:8001` / `120` | STT 주소 / 제한 시간(초) |
| `REDIS_HOST` / `REDIS_PORT` | `127.0.0.1` / `6379` | 대화 상태 저장소 |
| `CONVERSATION_TTL_SECONDS` | `86400` | Redis 상태 보존 시간(초) |
| `SESSION_COOKIE_SECURE` | `true` | HTTPS 세션 쿠키 |
| `BROWSER_TELEMETRY_OTLP_ENDPOINT` | 빈 값 | 브라우저 trace relay 목적지. 비어 있으면 비활성 |

Kubernetes의 실제 값은 [ss-k8s의 BE ConfigMap](https://github.com/say-scene/ss-k8s/blob/main/apps/ss-be/01_configmap.yaml)에 있다. LLM은 `vllm-qwen.vllm.svc.cluster.local:8000`의 `qwen3-4b-fp8`, STT는 `ss-triton:8001`, Redis는 `ss-redis:6379`를 사용한다.

## API

| 메서드·경로 | 역할 |
|---|---|
| `GET /api/v1/conversation` | 현재 대화 조회, 소유자 쿠키 발급 |
| `POST /api/v1/conversation/start` | 상황을 선택해 대화 시작 |
| `POST /api/v1/conversation/restore` | 브라우저의 완료된 대화 스냅샷 복원 |
| `POST /api/v1/conversation/turn` | 첫 인사 또는 사용자 발화·수정에 대한 응답 |
| `POST /api/v1/conversation/end` | 진행 중 대화 종료 |
| `POST /api/v1/stt` | multipart `audio` → 전사 텍스트 |
| `POST /api/v1/demo/cafe/suggestion` | 현재 UI의 답변 예문·키워드 추천 |
| `POST /api/v1/translate` | 한국어 번역 |
| `POST /api/v1/lookup` | 표현의 뜻·품사·예문 번역 |
| `POST /api/v1/report` | 최종 사용자 발화의 총평·교정 |
| `POST /api/telemetry/traces` | 브라우저 OTLP/JSON 수집. `/api/v1` 아님 |
| `GET /actuator/health`, `GET /actuator/prometheus` | 상태 확인 / 메트릭 |

- 소유자별 진행 중 대화는 하나다. `sayscene_session` 쿠키는 HttpOnly·SameSite=Lax이며 경로는 `/api/v1/conversation`이다. Redis의 버전 비교·원자적 갱신과 `requestId`로 중복·오래된 요청을 제어한다.
- 첫 인사는 상황별 사전 문장에서 선택하며 LLM을 호출하지 않는다. 이후 발화에는 상황 프롬프트와 전체 유효 이력을 전달한다. 마지막 사용자 발화 수정 시 revision을 올리고 후속 답변을 교체한다.
- 종료 시 Redis의 `active` 대화 텍스트를 비우되 종료 ID는 남겨 오래된 복원을 막는다. Redis 키 전체를 즉시 삭제하는 동작은 아니다. 리포트·단어장의 영구 보관은 브라우저가 담당한다.
- STT는 최대 2MiB의 WebM·MP4·Ogg·WAV를 받는다. BE는 파일 크기·MIME을 검사하며 녹음 시간 상한은 FE가 제어한다. 오디오 저장 기능은 없다.

## 관측성

```text
Micrometer /actuator/prometheus ← Prometheus
BE Java agent → Alloy OTLP → Tempo
FE OTLP/JSON → /api/telemetry/traces → Alloy OTLP → Tempo
Pod stdout/stderr → 로그 Alloy → Loki
```

`mvn compile`은 agent를 `target/otel-agent/opentelemetry-javaagent.jar`에 복사하고, Jib 이미지는 `/opt/otel/opentelemetry-javaagent.jar`에 포함한다. Kubernetes [Deployment](https://github.com/say-scene/ss-k8s/blob/main/apps/ss-be/04_deployment.yaml)는 `JAVA_TOOL_OPTIONS`로 agent를 로드하고 `service.name=ss-be`의 trace를 `http://alloy-otlp.monitoring.svc.cluster.local:4318`로 보낸다. 메트릭·로그는 별도 경로를 쓰므로 `OTEL_METRICS_EXPORTER=none`, `OTEL_LOGS_EXPORTER=none`이다.

브라우저 relay 목적지는 `http://alloy-otlp.monitoring.svc.cluster.local:4318/v1/traces`다. 비어 있으면 `204`로 비활성화하며, 잘못된 비어 있지 않은 주소는 `503`으로 처리한다. 원본 OTLP를 그대로 전달하지 않고 허용된 이름·속성만 재구성한다. 제한은 요청당 128KiB·128 spans, 인스턴스당 분당 60회·동시 4회다. 수집 endpoint는 사용자 인증이나 서비스 전체 quota가 아니다.

FE의 `speech.turn`은 STT 요청부터 대화 응답 처리까지 같은 trace로 묶는다. 녹음·TTS 재생 완료 시간이나 추론 서버 내부 계산 시간을 뜻하지 않는다. `session.id`는 페이지 메모리의 익명 UUID로 실제 대화 식별자와 별개다. 발화·오디오·body·쿠키·인증 헤더·예외 원문은 FE span/relay 전달 대상에서 제외하며, 수집 실패는 업무 요청을 막지 않는다.

상세 흐름·조회 예시는 [사용자 동작 tracing 문서](https://github.com/say-scene/ss-docs/blob/main/user-action-tracing.md)를 참고한다. 운영에서는 Grafana·Tempo의 FE→BE trace, Loki 로그와 Discord 알림을 확인했다. 이후 변경 시에도 이미지·ConfigMap 반영 후 실제 데이터를 조회해 확인한다.

## 배포

`main` push → AWS OIDC 인증 → `mvn -B compile jib:build` → ECR `ss-be:<SHA7>` push → GitHub App으로 `ss-k8s/apps/ss-be/kustomization.yaml` 태그 갱신 순서다. Docker 데몬은 필요 없다. 현재 CI에는 `mvn test` 단계가 없으므로 변경 전 별도로 실행한다.

GitHub의 `AWS_ROLE_ARN`, `SS_K8S_APP_ID`, `SS_K8S_APP_PRIVATE_KEY`와 `ss-k8s-write` environment를 사용한다. 실제 Kubernetes 반영은 Argo CD Sync 단계이며 이미지 push나 태그 커밋만으로 완료되지 않는다. Redis 상태를 readiness에 포함하고, HTTP 요청 histogram은 BE ConfigMap에서 활성화한다.

전체 서비스 구성은 [PRD & 아키텍처](https://github.com/say-scene/ss-docs/blob/main/english-speaking-prd-architecture-v1.md), 배포 리소스는 [ss-k8s](https://github.com/say-scene/ss-k8s)를 참고한다.
