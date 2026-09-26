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

### ✅ 적용됐다 (2026-09-26 · #25)

⚠️ **오랫동안 요구만 있고 구현이 없었다.** 코드는 MDC 를 성실히 채우는데
**출력 포맷이 그것을 버리고 있었고, 아무도 몰랐다** — 로그가 그냥 조금 덜 유용했을 뿐이라
실패로 보이지 않았기 때문이다. `MdcLogPatternTest` 가 **패턴 렌더링 자체**를 고정한다.

`application.yml` 의 **`logging.pattern.correlation`** 에 넣는다.

```yaml
logging:
  pattern:
    correlation: "[%X{repositoryId:-}|%X{candidateId:-}|%X{stage:-}|%X{attempt:-}] "
```

🔴 **`logback-spring.xml` 을 만들지 않는다.** Boot 기본 패턴에 이미 correlation 자리가
있고, 직접 어펜더를 정의하면 Boot 가 주는 것(색상·예외 축약·프로퍼티 바인딩)을 조용히 잃는다.
실제로 처음엔 XML 로 짰다가 `${CONSOLE_LOG_PATTERN:-…}` 의 기본값이 **영영 쓰이지 않는 것**을
테스트가 잡아냈다 — 그 변수를 Boot 의 `defaults.xml` 이 이미 정의한다.

⚠️ `%X{key:-}` 의 `:-` 를 빠뜨리면 값이 없을 때 리터럴 `key_IS_UNDEFINED` 가 찍힌다.

#### 🔴 MDC 에 넣어도 되는 것

| 키 | 값 | 넣는 곳 |
|---|---|---|
| `repositoryId` | 식별자 | `ScanExecutor` |
| `candidateId` | 식별자 | `AnalyzeIssuesUseCase` · `RecordingLanguageModel` |
| `stage` | **enum** | `ScanPipelineUseCase`(파이프라인) · `RecordingLanguageModel`(LLM) |
| `attempt` | 숫자 | `RecordingLanguageModel` |

**로그 포맷은 모든 줄에 붙으므로 여기가 오염되면 전부 오염된다.**
이슈 제목·본문·LLM 응답·예외 메시지를 넣지 않는다 — `MdcLogPatternTest` 가 소스를 훑어 막는다.

⚠️ **`stage` 는 두 어휘가 섞인다** — LLM 구간은 `LlmCallSite`, 파이프라인은 `PipelineStage` 다.
「지금 어느 단계인가」는 로그를 읽는 사람에게 하나의 질문이라 같은 키를 쓰기로 했다.

🔴 **중첩되면 안쪽이 바깥을 덮었다가 「되돌아와야」 한다 — 저절로 그렇게 되지 않는다.**
`finally` 에서 `remove` 로 끝내면 **복원이 아니라 삭제**다. 그러면 바깥이 넣어 둔 값이
안쪽 호출 이후 사라지고, **식별자가 가장 필요한 줄**(기각·실패 로그)에서 MDC 가 빈다.

```java
String previous = MDC.get("stage");     // ① 덮기 전에 챙긴다
MDC.put("stage", …);
try { … } finally {
    if (previous == null) { MDC.remove("stage"); } else { MDC.put("stage", previous); }  // ②
}
```

⚠️ 실제로 이 저장소가 한 번 그렇게 짰고, **문서에는 「되돌아온다」고 적혀 있었다.**
동작하지 않는 것이 규약으로 박혀 있으면 다음 리뷰의 판단 근거가 오염된다.

⚠️ **헬퍼로 묶고 싶어지는데, 그러면 키가 변수가 된다.** `MdcLogPatternTest` 의 소스
스캐너는 키가 리터럴·상수가 아니면 <b>실패</b>로 본다(정적으로 알 수 없으므로).
가드를 느슨하게 하느니 호출부가 장황한 편이 낫다.

⚠️ **풀 스레드는 재사용된다.** `MDC.remove` 를 빠뜨리면 다음 실행의 로그에 앞 실행의 값이
찍힌다 — 이어붙이려고 넣은 것이 **잘못 이어붙이게** 만든다.

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
