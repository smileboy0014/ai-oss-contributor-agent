# PLAN-42: 대역 프로파일 이름을 `test` → `fakes` 로 바꾼다

**이슈**: [#42](https://github.com/smileboy0014/ai-oss-contributor-agent/issues/42)
**type**: refactor
**작성일**: 2026-09-25
**작성자**: smileboy0014

> `refactor` 프로필이라 간략하다. **동작 보존**이 핵심 제약이다 — 테스트 결과가 그대로여야 한다.

---

## 1. 왜

#37(#4)이 만든 배선은 **프로파일 이름 하나**에 걸려 있다.

| 애노테이션 | 실제 의미 |
|---|---|
| `@ExternalAdapter` (src/main) | `@Profile("!test")` |
| `@FakeAdapter` (src/test) | `@Component @Profile("test")` |

**`test` 는 배포 환경 이름으로 매우 흔하다.** 누군가 `SPRING_PROFILES_ACTIVE=test` 로 띄우면
GitHub·LLM 어댑터와 전송 설정이 **통째로 사라진다.**

⚠️ **지금이 가장 조용한 시기다.** 아직 아무도 그 빈들을 주입하지 않아 **조용히 기동**한다.
#7 · #8 · #11 이 주입하기 시작하면 컨텍스트 실패로 바뀌어 시끄러워진다 —
즉 **늦을수록 비싸지는 것이 아니라, 늦으면 증상이 달라진다.** 지금은 「조용히 잘못 동작」이고
나중엔 「요란하게 실패」다. 조용한 쪽이 더 위험하다.

프로파일 네임스페이스는 앞으로 더 붐빈다 — Q-3 이 `web`/`worker` 분리를 예고하고 있다.

## 2. 무엇을 바꾸나 — 기능 4줄 + 문서 8파일

### 기능 — 4줄

| # | 파일 | 변경 |
|---|---|---|
| 1 | `support/ExternalAdapter.java` | `@Profile("!test")` → `@Profile("!fakes")` |
| 2 | `support/testing/FakeAdapter.java` | `@Profile("test")` → `@Profile("fakes")` |
| 3 | `support/testing/AgentIntegrationTest.java` | `@ActiveProfiles("test")` → `@ActiveProfiles("fakes")` |
| 4 | `support/testing/IntegrationTestProfileTest.java` | `REQUIRED_PROFILE = "fakes"` |

### 문서·메시지 — 8파일 (⚠️ 초안이 「한 줄」로 뭉갰던 것)

| 파일 | 왜 필수인가 |
|---|---|
| `.claude/rules/conventions/testing-philosophy.md` | 🔴 **규범이다.** 「합성 애노테이션엔 `@ActiveProfiles("test")` 를 포함하라」가 남으면, **규칙을 따른 사람이 실어댑터를 올린다**(S-1·S-2) |
| `.claude/docs/structure.md` | 🔴 `@FakeAdapter` 의 정의를 잘못 적게 된다 |
| `support/testing/ExternalAdapterIsolationTest.java` | 🔴 **가드 실패 메시지가 틀린 복구법을 알려준다** — 잡기는 잡는데 고치는 법이 `@Profile("!test")` + 삭제된 `FakeExternalDependencies` 다 |
| `config/LanguageModelConfig.java` · `agent/domain/FakeLanguageModel.java` | #10 의 javadoc |
| `support/testing/{AgentIntegrationTest,IntegrationTestProfileTest}.java` | javadoc·실패 메시지의 리터럴 |
| `support/testing/probe/{BypassProbe,ProbeUnprofiledBootstrap}.java` | 〃 |
| `.claude/docs/setup.md` | **신규** — 런타임 가드를 포기한 자리의 보완재(아래) |

⚠️ **과거 계획서(`PLAN-4.md`·`PLAN-43.md`)는 고치지 않는다.** 그 시점의 기록이고,
소급 수정하면 「왜 `test` 였나」의 근거가 사라진다.

### 캡슐화는 작동했다 — 다만 「한 줄도 안 건드린다」는 거짓이었다

프로파일 문자열이 **두 애노테이션 안에 캡슐화**돼 있어 **기능 변경은 4줄**로 갇힌다.
어댑터 저자는 `@ExternalAdapter` / `@FakeAdapter` 만 쓰므로 문자열을 모른다 —
#10 이 그 증거다(`LanguageModelConfig`·`FakeLanguageModel` 이 애노테이션을 그대로 채택).

⚠️ **초안은 여기서 「이 PR 은 #10 코드를 한 줄도 건드리지 않는다」고 적었는데 거짓이다.**
`LanguageModelConfig.java:25` 의 javadoc 이 `test` 프로필을 언급한다. **캡슐화되는 것은
동작이지 설명이 아니다** — 문서는 이름을 그대로 복사해 퍼뜨린다. 초안의 grep 이
`"test"`(따옴표)와 `Profile(` 만 봐서 `{@code test} 프로필` 표현을 통째로 놓쳤다.

### 왜 `fakes` 인가

| 후보 | 판정 |
|---|---|
| **`fakes`** | ✅ 채택. 배포 환경 이름으로 쓰일 일이 없고, `@Profile("!fakes")` 가 **이중부정이 아니다** |
| `no-external` | ❌ `@Profile("!no-external")` 이 이중부정이라 읽기 어렵다 |
| `test` (현행) | ❌ 환경 이름과 충돌 |

### 채택하지 않은 것 — `@ConditionalOnProperty` (이슈의 B안)

환경 이름과 **완전히** 분리되지만, 테스트마다 프로퍼티를 켜야 하고
`@AgentIntegrationTest` 가 `properties` 를 실어 나르는 형태가 된다.
Q-3 의 `web`/`worker` 와의 상호작용도 사라지지만, **지금 문제는 「이름 충돌」 하나**이고
이름만 바꾸면 해결된다. 더 큰 도구를 꺼낼 이유가 없다.

### 검토했으나 넣지 않은 것 — 런타임 방어

「`fakes` 프로파일이 운영에서 켜지면 기동 실패시킨다」는 가드를 생각했으나 넣지 않는다.
**「운영인지」를 판정할 방법이 없다** — 배포 프로파일이 아직 정해지지 않았고(Q-3),
잘못 판정하면 테스트가 기동하지 못한다.

🔴 **그래서 잔여 위험을 기록으로 남긴다 — 위험은 축소이지 제거가 아니다.**
`fakes` 도 평범한 프로파일 문자열이라 `SPRING_PROFILES_ACTIVE=fakes` 로 띄우면
**지금과 똑같이** 어댑터가 사라진다. 바뀐 것은 「실수로 켜질 확률」뿐이다.
가드를 포기한 대가로 두 곳에 금지를 명문화한다 — `ExternalAdapter` javadoc 과
`setup.md` 의 실행 절. **Q-3 이 배포 프로파일을 확정하면 런타임 가드를 다시 본다.**

## 3. 검증

| # | 무엇 | 어떻게 |
|---|---|---|
| 1 | **동작 보존** | 테스트 수·결과가 **바뀌지 않는다** (직전 `main` 기준과 대조) |
| 2 | **부분 적용이 잡히는가** | 가장 위험한 케이스를 재현한다 — `ExternalAdapter` 만 `!test` 로 남기고 나머지를 `fakes` 로. 실물·대역이 **동시에** 떠 주입 충돌이 나야 한다 |
| 3 | 프로파일 검사기가 여전히 문다 | `probe` 상시 표본이 잡히는지 (기존 테스트가 자동 확인) |
| 4 | **잔재 없음** | 3패턴으로 좁혀 grep — `Profile("test")` · `Profile("!test")` · `ActiveProfiles("test")` + javadoc 표현 `{@code test} 프로필`. ⚠️ 단순 `"test"` grep 은 `src/test/**` 경로·메서드명과 섞여 쓸모없다 |
| 5 | 전체 게이트 | `./gradlew build` — `exit=0` + `BUILD SUCCESSFUL` **양쪽** · CI green |
| 6 | **게이트 밖 경로** | ⚠️ 스위트 비교로는 안 보인다 — IDE·수동으로 `-Dspring.profiles.active=test` 를 쓰던 사람은 이 PR 이후 **말없이 실어댑터**를 받는다(실패가 아니라 실 네트워크 호출). PR 본문에 마이그레이션 노트로 남긴다 |

⚠️ 1번이 `refactor` 의 정의다. 테스트가 하나라도 늘거나 줄면 동작이 바뀐 것이다.

## 4. 게이트 판정

### 안전 경계

| 조항 | 접촉 | 어떻게 |
|---|---|---|
| **S-1 · S-2** | ✅ **간접** | 이 프로파일이 실어댑터를 테스트에서 빼는 장치다. 이름을 잘못 바꾸면 **차단이 통째로 풀린다** — 그래서 검증 2·3 이 필수다 |
| S-3 ~ S-6 | — | 샌드박스 실행·시크릿·대상 저장소 산출물·상태 전이 무변경 |

### 미결

| 항목 | 처리 |
|---|---|
| **Q-3** 프로필 분리 | **닫지 않는다.** 오히려 이 작업이 Q-3 을 위한 **네임스페이스를 비워 둔다** — `test` 를 놓아주면 `web`/`worker` 와 함께 쓸 환경 이름이 자유로워진다 |
| 그 외 | 미접촉 |

**닫는 미결 없음.**

## 5. 변경 이력

| 일자 | 작성자 | 변경 내용 |
|------|--------|----------|
| 2026-09-25 | smileboy0014 | 초안 — 이슈 #42 A안 |
| 2026-09-25 | smileboy0014 | **격리 검토 반영** — ① 「#10 코드를 한 줄도 안 건드린다」가 **거짓**이었다(`LanguageModelConfig.java:25`). grep 이 좁아 javadoc 표현을 놓쳤다 ② 규범 문서(`testing-philosophy.md`) 미갱신이 blocker — 규칙을 따른 사람이 S-1·S-2 위반을 만든다 ③ 가드 실패 메시지가 **틀린 복구법**을 알려주던 것 정정(삭제된 `FakeExternalDependencies` 동반 정리) ④ 잔여 위험(`fakes` 도 켜면 똑같이 사라진다) 명시 + `setup.md` 에 금지 신설 ⑤ 검증 4 의 grep 을 3패턴으로 구체화 · 검증 6(게이트 밖 경로) 추가 |
