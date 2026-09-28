package com.ossagent.candidate.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 착수 실행의 <b>진행 상태와 잠금</b> — #106.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>착수가 HTTP 스레드에서 동기로 돌았다 — clone + 3 × (LLM + 최대 30분 샌드박스). 프록시가 먼저 끊고,
 * 응답은 이미 낡은 {@code IMPLEMENTING} 이었다. 비동기로 돌리면 <b>두 가지</b>가 새로 필요하다:
 * 사람이 볼 <b>진행 상태</b>와, 같은 후보·같은 저장소를 겹쳐 돌리지 않는 <b>잠금</b>.
 *
 * <p>🔴 <b>저장소 단위 잠금</b>이 요점이다. 워크스페이스는 후보가 아니라 저장소마다 하나
 * ({@code JGitWorkspaceSource.fetch} 가 통째로 지우고 다시 받는다)라, 같은 저장소의 두 착수 —
 * 또는 착수와 PR 게이트 — 가 겹치면 서로의 트리를 지운다. 검증 중인 컨테이너의 파일이 사라져
 * 「코드가 틀렸다」로 기록되는 경로였다.
 *
 * <h2>⚠️ 프로세스 메모리다</h2>
 *
 * <p>{@code ScanExecutionRegistry} 가 #26 에서 DB 로 옮긴 것과 같은 한계 — 다중 인스턴스에서는 잠금이
 * 성립하지 않고, 재기동하면 진행 상태가 사라진다(후보 상태는 DB 에 있으므로 {@code GET /candidates/{id}}
 * 가 정본이다). 착수는 인스턴스 하나·동시 1건이 현재 운영 형태라(Q-3) 여기서 멈춘다.
 */
@Component
public class ImplementationRegistry {

    private final Map<Long, ImplementationProgress> byCandidate = new ConcurrentHashMap<>();
    /** 저장소 → 그 저장소의 워크스페이스를 쥔 후보 */
    private final Map<Long, Long> repositoryOwners = new ConcurrentHashMap<>();
    private final Clock clock;

    public ImplementationRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * 자리를 잡는다. 🔴 후보가 이미 활성이거나 <b>같은 저장소를 다른 후보가 쥐고 있으면</b> 거절한다.
     *
     * @return 잡았는가. 거절 사유는 {@link #stateOf}·{@link #isRepositoryBusy} 로 가른다
     */
    public synchronized boolean tryStart(Long candidateId, Long repositoryId) {
        require(candidateId, repositoryId);
        ImplementationProgress current = byCandidate.get(candidateId);
        if (current != null && current.phase().isActive()) {
            return false;
        }
        Long owner = repositoryOwners.get(repositoryId);
        if (owner != null && !owner.equals(candidateId)) {
            return false;
        }
        Instant now = clock.instant();
        byCandidate.put(candidateId, new ImplementationProgress(candidateId, repositoryId,
                ImplementationPhase.QUEUED, now, now, null));
        repositoryOwners.put(repositoryId, candidateId);
        return true;
    }

    public synchronized void markPhase(Long candidateId, ImplementationPhase phase) {
        ImplementationProgress current = byCandidate.get(candidateId);
        if (current == null) {
            return;
        }
        byCandidate.put(candidateId, current.with(phase, clock.instant(), current.message()));
    }

    /**
     * 끝났다 — 저장소 잠금을 푼다. 진행 상태는 남긴다(사람이 「왜」를 본다).
     *
     * @param message 🔴 우리 어휘로만 — 예외 본문·빌드 출력을 싣지 않는다 (S-4)
     */
    public synchronized void finish(Long candidateId, ImplementationPhase terminal, String message) {
        if (terminal == null || terminal.isActive()) {
            throw new IllegalArgumentException("마감 국면이 아니다: " + terminal);
        }
        ImplementationProgress current = byCandidate.get(candidateId);
        if (current == null) {
            return;
        }
        byCandidate.put(candidateId, current.with(terminal, clock.instant(), message));
        repositoryOwners.remove(current.repositoryId(), candidateId);
    }

    /** 자리를 되돌린다 — 잡았으나 실행에 들어가지 못했다(큐 거절 등). 흔적을 남기지 않는다. */
    public synchronized void release(Long candidateId) {
        ImplementationProgress current = byCandidate.remove(candidateId);
        if (current != null) {
            repositoryOwners.remove(current.repositoryId(), candidateId);
        }
    }

    public Optional<ImplementationProgress> stateOf(Long candidateId) {
        return Optional.ofNullable(byCandidate.get(candidateId));
    }

    /** 🔴 PR 게이트도 본다 — 그쪽도 같은 워크스페이스를 새로 받는다. */
    public boolean isRepositoryBusy(Long repositoryId) {
        return repositoryId != null && repositoryOwners.containsKey(repositoryId);
    }

    private static void require(Long candidateId, Long repositoryId) {
        if (candidateId == null || repositoryId == null) {
            throw new IllegalArgumentException("후보·저장소 식별자는 필수다");
        }
    }

    /** 착수 실행 1건의 진행 상태. */
    public record ImplementationProgress(Long candidateId, Long repositoryId,
            ImplementationPhase phase, Instant startedAt, Instant updatedAt, String message) {

        ImplementationProgress with(ImplementationPhase phase, Instant updatedAt, String message) {
            return new ImplementationProgress(candidateId, repositoryId, phase, startedAt, updatedAt, message);
        }

        public static ImplementationProgress idle(Long candidateId) {
            return new ImplementationProgress(candidateId, null, ImplementationPhase.IDLE, null, null, null);
        }
    }

    /**
     * 실행 국면. 후보의 <b>상태</b>({@code IMPLEMENTING}·{@code FAILED}…)와 다른 축이다 —
     * 이쪽은 「이 인스턴스가 지금 무엇을 하고 있나」다.
     */
    public enum ImplementationPhase {
        IDLE, QUEUED, PREPARING, SUCCEEDED, FAILED,
        /** 일시 장애로 미뤘다 — 후보는 {@code SELECTED} 로 되돌아갔다 (#98) */
        DEFERRED;

        public boolean isActive() {
            return this == QUEUED || this == PREPARING;
        }
    }
}
