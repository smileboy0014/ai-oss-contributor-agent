# 로깅 규칙

## 원칙

1. **시크릿은 절대 로그에 남기지 않는다** — [`safety-boundaries.md`](../context/safety-boundaries.md) S-4
2. **로그 레벨을 정확히 사용한다**
3. **구조화된 로그로 검색·집계 가능하게 한다** — 파이프라인 단계별 추적이 이 제품의 관측 그 자체다
4. **과도한 로그는 `debug` 로**

## 마스킹 — 절대 원문 금지

| 항목 | 처리 |
|------|------|
| `GITHUB_TOKEN` (`ghp_` · `gho_` · `github_pat_`) | **로그 금지.** 불가피하면 앞 4자 + `***` |
| `ANTHROPIC_API_KEY` (`sk-ant-`) | **로그 금지** |
| DB 비밀번호 · 커넥션 URL 의 자격증명 부분 | 금지 |
| 대상 저장소에서 읽은 파일 내용 | 그대로 찍지 않는다 — **저장소가 시크릿을 커밋해 뒀을 수 있다** |
| LLM 프롬프트 전문 | `debug` 레벨 + 스크럽 후에만. `info` 이상 금지 |
| 개인 식별 정보 (이슈 작성자 이메일 등) | 식별자만, 원문 금지 |

⚠️ **가장 흔한 사고는 예외 메시지다.** HTTP 클라이언트 예외에 요청 URL 이 담기고, URL 에 토큰이 붙어 있으면 그대로 나간다.

```java
// ❌ 요청 URL 에 토큰이 붙어 있으면 그대로 노출된다
log.error("GitHub 호출 실패: " + e.getMessage(), e);

// ✅ 우리가 통제하는 식별자만 남기고, 예외는 마지막 인자로
log.error("GitHub 호출 실패 repo={} stage={}", repo.fullName(), stage, e);
```

## 로그 레벨

| 레벨 | 용도 |
|------|------|
| `ERROR` | 사람 개입 필요. 파이프라인이 `FAILED` 로 떨어진 경우 |
| `WARN` | 재시도로 흡수된 실패 · 레이트리밋 지연 · 정책 파싱 보류 |
| `INFO` | **파이프라인 단계 전이** (스캔 시작·후보 생성·상태 전이·PR 생성) |
| `DEBUG` | LLM 프롬프트·응답 요약, 샌드박스 명령. 운영 OFF |
| `TRACE` | 매우 상세. 운영 OFF |

## 기본형

```java
private static final Logger log = LoggerFactory.getLogger(ScanIssuesUseCase.class);

log.info("스캔 시작 repo={} cursor={}", repo.fullName(), cursor);
log.info("후보 생성 repo={} issue=#{} candidateId={}", repo.fullName(), issueNumber, candidateId);
log.warn("레이트리밋 임박 remaining={} resetAt={} — 지연 후 재개", remaining, resetAt);
log.error("샌드박스 실행 실패 candidateId={} stage={}", candidateId, stage, e);   // 예외는 마지막 인자
```

## 금지

```java
// ❌ System.out.println
System.out.println("debug");

// ❌ 토큰·키 평문
log.info("token={}", githubToken);

// ❌ 스택트레이스 유실
log.error("실패: " + e.getMessage());

// ❌ 대상 저장소 파일 내용 통째로
log.info("파일 내용: {}", fileContent);
```

## MDC — 파이프라인 추적

한 후보가 여러 단계·여러 재시도를 거치므로, **식별자 없이는 로그를 이어붙일 수 없다.**

```java
MDC.put("candidateId", candidateId);
MDC.put("stage", stage.name());
MDC.put("attempt", String.valueOf(attempt));
try {
    // ...
} finally {
    MDC.clear();
}
```

로그 포맷에 `%X{candidateId} %X{stage} %X{attempt}` 를 포함시킨다.

## 반드시 남겨야 할 것

| 지점 | 남길 것 |
|---|---|
| 대외 호출 | 대상 · 소요 시간 · 결과 코드. **레이턴시는 전부 기록** |
| LLM 호출 | 모델 · 입출력 토큰 수 · 소요 시간 (본문 아님) — 비용이 보이지 않으면 재시도 루프가 조용히 돈을 태운다 |
| 상태 전이 | **이전 → 이후** 값 |
| 재시도 | 몇 번째인지 · 직전 실패 사유 |
| 샌드박스 | 이미지 · 명령 · 종료 코드 · 소요 시간 · **컨테이너 정리 결과** |
| 안전 게이트 | Fork owner 검증 · draft 고정 · 규약 판정 결과 — **통과한 것도 남긴다.** 사고 후 「막았는가」를 증명할 수 있어야 한다 |

## 남기지 말아야 할 것

- 위 마스킹 표의 원문
- 대용량 payload 원본 (diff 전문 · 파일 내용 · 프롬프트 전문) — 크기와 해시만
- 대상 저장소에서 읽은 임의 텍스트를 **포맷 문자열로** 쓰는 것 (로그 인젝션)

### 프롬프트 경로는 테스트가 본다 (2026-09-26 · #28)

`agent` 패키지의 `log.*` 인자에 본문성 값이 오면 `PromptBoundaryTest` 가 잡는다.
같은 테스트가 `com.anthropic` SDK 사용처도 어댑터와 `config` 로 제한한다 —
SDK 를 직접 쓰는 자리에는 **`PromptScrubber` 가 걸릴 이음매가 없다.**

**통과하는 형태와 막히는 형태**

```java
log.warn("프롬프트에서 시크릿 패턴을 치환했다 (원문 길이={})", text.length());   // ✅ 스칼라
log.info("LLM 호출 성공 inputTokens={}", response.usage().inputTokens());      // ✅ 스칼라
log.debug("프롬프트={}", prompt);                                              // ❌ 맨몸
log.debug("응답 요약={}", response.content());                                 // ❌ 본문 접근자
```

⚠️ **이름으로 보는 검사이지 타입 검사가 아니다.** 오탐이 나면 변수명을 바꾸거나 스칼라
접근자를 쓴다 — **검사를 지우지 않는다.** 오탐이 잦으면 대상 패키지를 더 좁힌다.

⚠️ 「프롬프트 전문은 `debug` + 스크럽 후에만」이 여전히 유효하지만, **지금 그런 코드는
없다.** 필요해지면 위 검사가 먼저 막으므로 예외를 의도적으로 뚫어야 한다 — 그게 요점이다.

## 저장 vs 로그

diff · 테스트 출력 · 리뷰 결과는 **로그가 아니라 DB**(`GeneratedChange` · `AgentRun`)에 남긴다.
로그는 「무슨 일이 언제 일어났나」, DB 는 「무엇이 만들어졌나」다. 섞으면 둘 다 못 쓴다.

DB 에 넣을 때도 **시크릿 스크럽은 동일하게 적용**한다 — `AgentRun.errorMessage` 에 토큰이 섞이는 것이 실제로 잦다.
