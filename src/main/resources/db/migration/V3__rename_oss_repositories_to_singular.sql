-- 테이블명을 단수로 통일한다.
--
-- V1 의 oss_repositories 만 복수였다. PRD §22 ERD 가 단수(REPOSITORY · ISSUE · AGENT_RUN …)이고
-- JPA/Hibernate 기본값도 단수라, 정본 문서와 코드가 어긋나 있었다.
--
-- ⚠ V1 을 직접 고치지 않는다. 이미 적용된 마이그레이션의 내용을 바꾸면 Flyway 체크섬이 깨져
--   그 DB 는 기동하지 못한다. 적용된 것은 손대지 않고 새 버전을 쌓는다.
--
-- V2 의 6테이블은 처음부터 단수로 만들었으므로 여기서 다룰 것이 없다.

ALTER TABLE oss_repositories RENAME TO oss_repository;

-- 제약·인덱스 이름도 따라 바꾼다. 남겨 두면 「테이블은 단수, 제약은 복수」로 갈라진다.
ALTER TABLE oss_repository RENAME CONSTRAINT uk_oss_repositories_url TO uk_oss_repository_url;
ALTER INDEX idx_oss_repositories_enabled_last_scanned RENAME TO idx_oss_repository_enabled_last_scanned;
