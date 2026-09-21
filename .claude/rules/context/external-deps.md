# 외부 의존

> 전부 **경계 밖**이다. 우리 트랜잭션 안에서 호출하지 않는다 — 실패·지연이 우리 DB 락을 잡는다.

## GitHub API

| 항목 | 내용 |
|---|---|
| 용도 | 저장소 메타데이터 · open 이슈 조회 · 기여 규약 파일 조회 · Fork 생성 · push · Draft PR 생성 |
| 인증 | `GITHUB_TOKEN` (PAT) 또는 GitHub App 설치 토큰 |
| **권한** | 원본은 **읽기만**. 쓰기는 사용자 Fork 에 한정 — [`safety-boundaries.md`](./safety-boundaries.md) S-1 |
| 레이트리밋 | 인증 5,000 req/h. Search API 는 별도(30 req/min) — **스캐너가 가장 먼저 부딪힌다** |
| 미결 | 클라이언트 라이브러리 미선정 ([`open-questions.md`](./open-questions.md) Q-1) |

**설계 제약**
- 이슈 수집은 `updated_at` 커서 + `ETag` 조건부 요청으로 증분화한다. 매 스캔 전량 조회는 레이트리밋을 태운다
- `X-RateLimit-Remaining` 이 임계 미만이면 **작업을 실패시키지 말고 지연**시킨다. 리밋 소진은 정상 운영 상황이다
- 2차 레이트리밋(abuse detection)은 429 가 아니라 403 으로 온다. 403 을 권한 오류로만 처리하면 무한 재시도에 빠진다

## LLM API

| 항목 | 내용 |
|---|---|
| 용도 | 이슈 분석 · 구현 계획 · 코드 생성 · diff 리뷰 (PRD §6.1 의 4개 지점) |
| 인증 | `ANTHROPIC_API_KEY` |
| 모델 | `ANTHROPIC_MODEL` — 기본 `claude-sonnet-5` |

**설계 제약**
- **저장소 전체를 넘기지 않는다** (PRD §12). 키워드 → 코드 검색 → 관련 파일로 단계적으로 좁힌다
- 프롬프트에 넣기 전 시크릿 스크럽 — S-4. 이 경로가 가장 흔한 유출구다
- 호출마다 `AgentRun` 에 입출력 토큰을 기록한다. 비용이 보이지 않으면 재시도 루프가 조용히 돈을 태운다
- 출력은 **신뢰하지 않는다.** 계획은 검증하고, 코드는 빌드·테스트로 거른다. 모델 응답을 그대로 진실로 쓰는 경로를 만들지 않는다
- 타임아웃·재시도를 어댑터에서 명시한다. 재시도 상한은 `agent.execution.max-retries`

## Docker 샌드박스

| 항목 | 내용 |
|---|---|
| 용도 | 대상 저장소 clone · build · test · lint |
| 이미지 | `SANDBOX_DOCKER_IMAGE` (기본 `eclipse-temurin:21-jdk`) |
| 제한 | 네트워크 `none` · CPU · 메모리 · 타임아웃 — S-3 |

**설계 제약**
- 대상 저장소마다 Java 버전·빌드 도구가 다르다. `RepositoryPolicy` 의 값으로 이미지·명령을 고른다
- 네트워크를 끊으면 의존성 해석이 실패한다. **의존성 워밍 단계와 빌드 단계를 분리**하고, 워밍에만 네트워크를 연다
- 컨테이너는 실행마다 새로 만들고 끝나면 지운다. 상태를 재사용하면 앞 실행의 산출물이 다음 판정을 오염시킨다
- 미결: 워밍 정책·캐시 볼륨 설계 ([`open-questions.md`](./open-questions.md) Q-4)

## PostgreSQL

| 항목 | 내용 |
|---|---|
| 용도 | 저장소·이슈·후보·실행이력·PR 영속화 |
| 로컬 | `docker compose up -d` (`postgres:17-alpine`) |
| 기본값 | **미설정 시 H2 in-memory** — `application.yml` 이 그렇게 되어 있다 |

⚠️ 지금 스키마 관리는 `ddl-auto: update` 다. 운영에 쓸 수 없다 — 마이그레이션 도구 도입은 Q-2.

## Redis

| 항목 | 내용 |
|---|---|
| 용도(예정) | 잡 큐(`oss.scan` → `oss.analyze` → …), 스캔 커서 캐시 |
| 현재 | **애플리케이션이 쓰지 않는다.** `docker-compose.yml` 에만 있다 |

PRD §21 은 「MVP 는 Scheduler + DB 로 시작하고, Worker 분리가 필요해지면 Redis Streams 를 적용」이라고 정했다.
지금 Redis 의존을 넣는 것은 이 결정을 앞서간다.

## 공통 규율

| 규율 | 이유 |
|---|---|
| **트랜잭션 밖에서 호출** | 대외 호출 지연이 DB 커넥션·락 점유로 번진다 |
| **능력 인터페이스는 domain 에, 구현은 adapter/out 에** | [`../conventions/architecture.md`](../conventions/architecture.md) 규율 ③ |
| **타임아웃·재시도·실패 처리를 어댑터에서 명시** | 기본값에 맡기면 무한 대기가 생긴다 |
| **응답을 그대로 신뢰하지 않는다** | GitHub 응답은 스키마 검증, LLM 응답은 빌드·테스트 검증 |
