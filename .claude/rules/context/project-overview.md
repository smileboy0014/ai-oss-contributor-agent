# 프로젝트 개요

> 기준일 **2026-09-18**. 근거 문서는 [`docs/ai-oss-contributor-agent-prd.md`](../../../docs/ai-oss-contributor-agent-prd.md) (v1.1, Draft).
> ⚠️ **PRD 는 Draft 다.** 미확정 항목은 [`open-questions.md`](./open-questions.md) 에 모아 두었다 — **구현 착수 전 확인**한다.

## 무엇을 만드나

Java/Spring 오픈소스의 **GitHub Issue 를 찾아 Draft PR 까지 준비하는** 에이전트.
사람이 승인하기 전까지가 자동화 범위이고, **실제 제출·수정·머지는 사람이 한다.**

```
탐색 ──▶ 분석 ──▶ 계획 ──▶ 구현 ──▶ 검증 ──▶ AI 리뷰 ──▶ Draft PR ──▶ 사람 승인
이슈 수집  기여 가능성  구현 계획  샌드박스   빌드·테스트   diff 검토    Fork 에 push   ← 여기서 끝
```

**핵심 질문**(PRD §28) — *개발자가 Spring Kafka 에서 기여할 만한 Issue 하나를 고르면, 시스템이 실제 코드를 고치고 테스트한 뒤 검토 가능한 Draft PR 까지 만들어줄 수 있는가?*
이 End-to-End 하나가 MVP 성공 기준이다. 저장소 개수·이슈 처리량이 아니다.

## 제품 정의 — AI Code Generator 가 아니다

PRD §30 이 못 박는다. 이 제품의 본질은 **Software Engineering Workflow Automation** 이다.
비결정적인 LLM 출력을 **빌드·테스트·리뷰라는 결정적 게이트**로 걸러내는 파이프라인이 제품이고, 코드 생성은 그 안의 한 단계다.

따라서 품질의 축은 「좋은 코드를 쓰는가」가 아니라 **「나쁜 결과를 걸러내는가」** 다. 게이트를 느슨하게 만드는 변경은 기능 추가가 아니라 제품 훼손이다.

## 범위

| 포함 (MVP) | 제외 (Non-Goals · PRD §3) |
|---|---|
| 대상 저장소 동적 등록 · 기여 규약 자동 분석 | **자동 Merge** |
| 이슈 수집 · 필터 · LLM 기여 가능성 분석 | **원본 저장소 직접 Push** |
| 관련 소스 탐색 · 구현 계획 수립 | **모든 언어 지원** — Java/Spring 만 |
| 샌드박스 코드 수정 · 테스트 · 실패 시 재시도(최대 3) | **모든 저장소 자동 구현** |
| AI diff 리뷰 · PR 템플릿 적용 · **Draft** PR 생성 | **검증되지 않은 코드의 자동 제출** |
| 작업 전체 이력·토큰·비용 저장 | |

**Non-Goals 는 「나중에 할 것」이 아니라 「하지 않을 것」이다.** 왜 그런지는 [`safety-boundaries.md`](./safety-boundaries.md).

## 대상 저장소 Phase

| Phase | 저장소 |
|---|---|
| 1 | `spring-projects/spring-kafka` |
| 2 | `spring-projects/spring-framework` · `spring-data-redis` · `spring-boot` · `reactor/reactor-core` |
| 3 | `apache/kafka` · `redis/redis` |

Phase 1 은 **한 저장소로 좁힌 것이 의도**다. 저장소마다 기여 규약·빌드 방식이 달라, 하나를 끝까지 통과시키기 전에 넓히면 어느 것도 완성되지 않는다.

## 시스템 구성 — 모듈러 모놀리스

MVP 라 **인스턴스 하나**로 간다. 안에서 도메인을 갈라 나중에 떼어낼 수 있게 한다 (PRD §6.2).

| 도메인 | 한 줄 정의 | 상태 |
|---|---|---|
| `repository` | 어떤 저장소를 보고 있고, **그 저장소의 기여 규약이 무엇인가** | 골격 있음 |
| `issue` | 그 저장소에 **어떤 이슈가 있고 무엇이 후보 자격이 있는가** | 비어 있음 |
| `candidate` | 이 이슈가 **기여 가능한가 · 지금 어느 단계인가** (상태머신) | enum 만 |
| `agent` | LLM·샌드박스 실행 — **분석·계획·코딩·리뷰의 실행 이력과 비용** | 비어 있음 |
| `pullrequest` | Fork·브랜치·**Draft PR** 메타데이터 | 비어 있음 |
| `support` | 도메인 없는 공통 (web 예외 매핑 · GitHub 클라이언트) | 예외 매핑만 |

실행 프로필은 둘로 갈 예정 — `web`(API) / `worker`(스캐너·코딩·검증). 아직 분리하지 않았다 ([`open-questions.md`](./open-questions.md) Q-3).

경계 밖: **GitHub API** · **LLM API** · **Docker 샌드박스** · PostgreSQL · Redis. 상세는 [`external-deps.md`](./external-deps.md).

## 지금 어디까지 와 있나

코드는 **API 경계만 있는 골격**이다. 착각하지 말 것 — 아래는 전부 아직 없다.

- 이슈 수집(GitHub API 호출) · LLM 호출 · 샌드박스 실행 · PR 생성 — **구현 0**
- `POST /api/repositories/{id}/scan` 은 **요청 사실만 기록**하고 실제 스캔을 하지 않는다
- `GET /api/candidates` · `GET /api/candidates/{id}` 는 **동작한다**(필터·페이지네이션·상세). 후보 적재 경로(#11)도 들어와 **`ANALYZED` 후보가 실제로 쌓인다** — 다만 이슈를 넣는 스캔 트리거(#14)가 아직 없어 수동 호출로만 돈다
- ERD **7테이블 전부 존재**(Flyway `V1`·`V2`) · 엔티티 7개 매핑 완료 — 다만 **읽고 쓰는 코드가 없다**
- Redis 는 `docker-compose` 에만 있고 애플리케이션이 쓰지 않는다

## 성공 지표

| 지표 | 왜 |
|---|---|
| **End-to-End 완주율** — 후보 1건이 Draft PR 까지 도달 | MVP 정의 그 자체 |
| **사람 승인율** — 만들어진 Draft PR 중 사람이 제출할 만하다고 판단한 비율 | 게이트가 실제로 작동하는지의 유일한 증거 |
| 재시도 소진율 · 토큰 비용 | 파이프라인이 경제적으로 성립하는가 |

PR 머지 수는 **메인테이너 사정에 좌우되므로 제품 지표로 쓰지 않는다.**
