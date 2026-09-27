-- 이슈 #68 — 규약 문서가 바뀌었는지를 「판정이 섰는지」와 분리해서 안다
--
-- ⚠ 머지 순서: V8(#24 보류 해소) → V9(여기). baseline-on-migrate 가 false 라 순서가
--   역전되면 Flyway 가 out-of-order 로 보고 validate 에서 기동을 막는다.
--   H2 는 매번 새로 떠서 드러나지 않고 PostgreSQL 개발 DB 에서만 터진다 — Q-2b.
--
-- ⚠ 벤더 고유 문법을 쓰지 않는다 (Q-2). H2·PostgreSQL 양쪽에서 같은 SQL 이 돈다.

-- ─────────────────────────────────────────────────────────────
-- 🔴 왜 필요한가 — 「못 읽었다」와 「읽었는데 그대로다」가 구분되지 않는다
-- ─────────────────────────────────────────────────────────────
-- 지금은 둘 다 판정이 안 바뀐다. 그래서 이런 상태가 탐지되지 않은 채 굳는다.
--
--   1. 읽었다 → 금지 문구 없음 → ai_contribution_allowed = TRUE
--   2. 저장소가 AGENTS.md 에 「AI 생성 기여 금지」를 추가한다
--   3. 그 문서가 우리 상한을 넘거나 5xx 를 준다 → 판정이 서지 않는다
--   4. reanalyze 가 거부한다 → 판정은 TRUE 로 남는다
--   5. 우리는 계속 Draft PR 을 만든다   ← 🔴 S-5 위반이 진행 중인데 아무도 모른다
--
-- 지문이 있으면 3단계에서 「바뀌었다」를 판정과 **독립적으로** 알 수 있다.

-- 후보 경로별 지문. 형식은 `path=fingerprint;path=fingerprint` 이고
-- fingerprint 는 `absent` 또는 소문자 hex 64자(SHA-256)다.
--
-- 🔴 NULL 을 허용하는 것이 요점이다. 기존 행은 비교 기준이 없고, 그것이
--   「바뀌지 않았다」로 읽히면 안 된다. 「없다」와 「같다」를 섞는 순간
--   이 기능을 배포하는 순간 모든 저장소가 일괄 보류로 떨어지거나(반대로)
--   변경이 영영 탐지되지 않는다 — PolicyDocumentFingerprints javadoc.
--
-- ⚠ TEXT 가 아니라 VARCHAR 다. SHA-256 은 단방향이라 대상 저장소 원문이 복원되지 않고,
--   경로는 PolicyDocumentPath 의 우리 상수 목록에서만 온다 — 외부 텍스트가 아니므로
--   @ExternalText 마커도 스크럽 등록 행도 필요 없다 (S-4).
--   repository_policy.pending_reason(V5) · resolution_note(V8)와 같은 취급이고,
--   ExternalTextMarkerTest 가 「TEXT 로 매핑된 필드는 모두 마커를 갖는다」를 강제하므로
--   타입 선택이 곧 그 선언이다.
--
-- ⚠ 4000 인 근거 — 경로 13개 × (경로 ~32자 + '=' + 지문 64자 + ';') ≈ 1,300자.
--   PolicyDocumentFingerprintsTest 가 전 경로 직렬화가 이 상한 안에 드는지 단언한다.
--   경로를 늘릴 때 그 테스트가 먼저 빨개진다
ALTER TABLE repository_policy
    ADD COLUMN document_fingerprints VARCHAR(4000);

-- 🔴 「판정」이 아니라 「확인」의 시각이다. analyzed_at 과 다르다.
--
--   analyzed_at            — LLM 을 불러 판정을 세운 시각
--   documents_checked_at   — 규약 문서를 실제로 다시 읽어 본 시각 (판정 여부와 무관)
--
-- 지문이 같으면 LLM 을 부르지 않으므로 analyzed_at 은 전진하지 않는다.
-- 그때 analyzed_at 을 갱신하면 「분석했다」가 거짓말이 된다.
--
-- 🔴 이 컬럼이 5xx 가 계속되는 경우의 유일한 증거다. 응답을 못 받으면 지문을
--   구할 수 없어 「바뀌었는가」를 원리적으로 알 수 없는데, 그때 이 값이 **멈춰 있다.**
--   「모르는 상태가 얼마나 오래됐는가」가 여기서만 보인다.
ALTER TABLE repository_policy
    ADD COLUMN documents_checked_at TIMESTAMP(6) WITH TIME ZONE;
