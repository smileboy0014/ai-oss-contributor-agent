package com.ossagent.repository.application;

/**
 * 스캔 실행 행이 <b>없다</b> — 🔴 「잠겨 있다」가 아니다 (#26).
 *
 * <h2>왜 따로 드러내는가</h2>
 *
 * <p>자리 잡기는 조건부 UPDATE 한 방이라 <b>0행</b>이 두 가지를 뜻한다 —
 * 「남이 잡고 있다」와 「행이 아예 없다」. 둘을 합쳐 「잠겨 있다」로 번역하면
 * <b>등록 버그가 영구 409 로 위장된다.</b> 그 저장소는 아무리 기다려도 안 풀리는데
 * 사람에게는 「누가 스캔 중」으로 보인다.
 *
 * <p>행은 두 경로로 <b>항상</b> 만들어진다 — V10 백필(기존)과
 * {@code RegisterRepositoryUseCase}(신규, 저장소와 같은 트랜잭션). 따라서 이 예외가
 * 뜨는 것은 <b>그 둘 중 하나가 깨졌다는 신호</b>다.
 *
 * <p>⚠️ <b>여기서 행을 만들어 주지 않는다.</b> 만들어 주면 두 인스턴스가 동시에
 * 만들려 해 경쟁이 생기고, 그 경쟁을 원자적으로 푸는 수단(upsert)이 바로 벤더 고유
 * 문법이라 Q-2b-1 에 걸린다. 무엇보다 <b>고장을 조용히 덮는다.</b>
 */
public class ScanExecutionNotRegisteredException extends RuntimeException {

    private final transient Long repositoryId;

    public ScanExecutionNotRegisteredException(Long repositoryId) {
        super("스캔 실행 행이 없다: repositoryId=" + repositoryId);
        this.repositoryId = repositoryId;
    }

    public Long repositoryId() {
        return repositoryId;
    }
}
