-- 이슈 #26 — 스캔 실행 상태를 프로세스 메모리에서 DB 로 · 저장소별 스캔 주기
--
-- ⚠ 벤더 고유 문법을 쓰지 않는다 (Q-2b-1). H2·PostgreSQL 양쪽에서 같은 SQL 한 벌이 돈다.
--   ON CONFLICT(PostgreSQL) · MERGE(H2) · JSONB · FOR UPDATE SKIP LOCKED 금지.
--   아래 설계는 그것들을 **필요로 하지 않게** 짜였다 — 행을 항상 존재하게 만들어
--   upsert 자체를 없앴다.

-- ─────────────────────────────────────────────────────────────
-- 🔴 왜 별도 테이블인가 — last_scanned_at 옆 컬럼이 아니다
-- ─────────────────────────────────────────────────────────────
-- 실행 상태는 직전 실행의 집계(ScanPipelineResult)를 들고 있다 — 수치 6개 + 플래그.
-- 루트(oss_repository)에 펴면 컬럼이 열 개 넘게 붙고 저장소를 읽을 때마다 따라온다.
-- architecture.md 「애그리거트는 작게 유지한다」.
--
-- repository 애그리거트 **안**이므로 물리 FK 를 건다 (규율 ④ — 경계를 넘을 때만 막는다).
-- agent_run·generated_change 와 달리 이 테이블은 **저장소당 1행이고 덮어쓴다** —
-- 무한정 자라지 않으므로 멤버가 맞다.

CREATE TABLE scan_execution
(
    -- 저장소당 1행. PK 가 곧 FK 다
    repository_id    BIGINT       NOT NULL PRIMARY KEY,

    -- IDLE · QUEUED · RUNNING · SUCCEEDED · SKIPPED · FAILED (ScanPhase)
    phase            VARCHAR(20)  NOT NULL,

    started_at       TIMESTAMP(6) WITH TIME ZONE,
    finished_at      TIMESTAMP(6) WITH TIME ZONE,

    -- ─────────────────────────────────────────────────────────
    -- 🔴 리스 — 없으면 방어가 스스로를 잠근다
    -- ─────────────────────────────────────────────────────────
    -- 메모리 구현에는 아무도 적어 두지 않은 안전장치가 있었다: **재기동이 상태를 지운다.**
    -- DB 로 옮기는 순간 그것이 사라진다. 인스턴스가 kill -9 되면 RUNNING 행이 남고
    -- 그 저장소는 영원히 409 가 된다 — 재기동해도 안 풀린다.
    --
    -- external-deps.md 에 사고로 기록된 그 모양 그대로다(resetAt 을 모를 때 갱신할
    -- 응답이 영영 오지 않아 모든 GitHub 호출을 실패시킨 선제 차단).
    --
    -- 가르는 것은 보수성이 아니라 실패의 방향이 되돌릴 수 있는가다:
    --   리스를 둔다     → 최악이 「중복 스캔 1회」. 수집이 멱등(upsert)이라 되돌릴 수 있다
    --   리스가 없다     → 저장소가 영구히 잠긴다. 사람이 DB 를 고치는 것 외에 수단이 없다
    lease_expires_at TIMESTAMP(6) WITH TIME ZONE,

    -- 누가 잡았나 — **진단용이다. 판정에 쓰지 않는다.**
    -- 판정에 쓰면 「내가 잡은 것만 내가 놓을 수 있다」가 되어, 죽은 인스턴스의 토큰이
    -- 남은 행을 아무도 놓지 못한다. 그것이 바로 위가 막으려는 영구 잠금이다
    owner_token      VARCHAR(64),

    -- 🔴 예외 **클래스 이름**만. 메시지를 넣지 않는다 (S-4) — 메시지에는 요청 URL ·
    --    모델 응답 · 대상 저장소 텍스트가 실려 오고 그것이 진행 조회로 HTTP 에 나간다
    failure_stage    VARCHAR(40),
    failure_type     VARCHAR(255),

    -- 직전 실행의 집계 (ScanPipelineResult)
    issues_saved         INTEGER NOT NULL DEFAULT 0,
    issues_judged        INTEGER NOT NULL DEFAULT 0,
    candidates_analyzed  INTEGER NOT NULL DEFAULT 0,
    candidates_rejected  INTEGER NOT NULL DEFAULT 0,
    candidates_failed    INTEGER NOT NULL DEFAULT 0,
    candidates_skipped   INTEGER NOT NULL DEFAULT 0,
    has_more             BOOLEAN NOT NULL DEFAULT FALSE,

    -- 🔴 실패가 아니라 **지연**이다 (레이트리밋). 이제 DB 에 남아 재기동 후에도 보인다
    delayed_until    TIMESTAMP(6) WITH TIME ZONE,
    skip_reason      VARCHAR(40),

    -- 「한 번도 돌지 않았다」와 「돌았는데 집계가 0 이다」를 가른다.
    -- 이것이 없으면 IDLE + 수치 0 인 행을 보고 어느 쪽인지 알 수 없다
    has_result       BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_scan_execution_repository
        FOREIGN KEY (repository_id) REFERENCES oss_repository (id)
);

-- 만료된 리스를 훑는 질의가 보는 축
CREATE INDEX idx_scan_execution_phase_lease ON scan_execution (phase, lease_expires_at);

-- ─────────────────────────────────────────────────────────────
-- 🔴 행이 항상 존재하게 만든다 — upsert 를 쓰지 않기 위해서다
-- ─────────────────────────────────────────────────────────────
-- 자리 잡기는 조건부 UPDATE 한 방으로 원자성을 얻는다(SELECT 후 UPDATE 로 짜면 두
-- 인스턴스가 그 사이를 통과해 둘 다 스캔을 시작한다). UPDATE 는 행이 있어야 하는데,
-- 「없으면 만든다」를 원자적으로 하려면 ON CONFLICT·MERGE 가 필요하고 그것이 벤더
-- 고유 문법이다. 그래서 행을 **항상 있게** 한다:
--   · 기존 저장소 → 아래 백필
--   · 신규 등록   → OssRepository 생성자가 저장소와 함께 만든다 (같은 트랜잭션 · #26 구현 중 변경)
--
-- ⚠ 그래서 「행이 없다」는 정상 상태가 아니다. tryStart 가 0행을 받으면 그것이
--   「남이 잡았다」인지 「행이 없다」인지 구분해야 한다 — 존재 확인을 분리해 행이
--   없으면 예외로 드러낸다. 조용히 「잠겨 있다」로 번역하면 등록 버그가 영구 409 로
--   위장된다.
INSERT INTO scan_execution (repository_id, phase)
SELECT id, 'IDLE'
FROM oss_repository;

-- ─────────────────────────────────────────────────────────────
-- 저장소별 스캔 주기 (완료조건 1)
-- ─────────────────────────────────────────────────────────────
-- NULL 이면 전역 기본값(scan.default-interval)을 쓴다. 저장소마다 이슈가 쌓이는
-- 속도가 다른데 전역 주기 하나면 활발한 저장소는 늦고 조용한 저장소는 레이트리밋을
-- 태운다.
--
-- 🔴 주기 판정이 보는 것은 last_scanned_at 이고, 그것은 「요청 시각」이라
--   **실패해도 전진한다.** 즉 실패한 스캔도 주기를 소모한다 — 결함이 아니라 의도다.
--   계속 실패하는 저장소가 매 주기마다 GitHub·LLM 을 태우는 쪽이 더 나쁘다.
--   「조용히 스캔 안 됨」은 scan_execution.phase = FAILED 와 메트릭에 드러난다.
ALTER TABLE oss_repository
    ADD COLUMN scan_interval_minutes INTEGER;
