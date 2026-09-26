package com.ossagent.repository.application;

/**
 * 파이프라인의 <b>어느 단계</b>가 실패했는지를 실어 나른다.
 *
 * <p>🔴 <b>예외 원문을 담지 않는다</b> (S-4). {@link #failureType} 은 원인 예외의
 * <b>클래스 이름</b>뿐이다 — 메시지에는 요청 URL·모델 응답·대상 저장소 텍스트가
 * 실려 올 수 있고, 그것이 진행 조회 응답과 로그로 나간다.
 *
 * <p>원인 예외 자체는 {@code cause} 로 붙여 스택트레이스를 잃지 않는다. 그것은
 * 로거가 마지막 인자로 받아 처리하고, <b>사람이 보는 응답에는 타입만</b> 간다.
 *
 * <p>{@link #partial} 은 실패 전까지의 성과다. 수집은 됐는데 분석이 죽었다고
 * 0으로 보고하면 사람이 「아무 일도 없었다」로 읽는다 (NFR-4).
 */
public class ScanStageFailedException extends RuntimeException {

    private final transient ScanExecutionState.Stage stage;
    private final transient ScanPipelineResult partial;

    private ScanStageFailedException(ScanExecutionState.Stage stage, Long repositoryId,
            Throwable cause, ScanPipelineResult partial) {
        super("스캔 파이프라인 단계 실패: stage=%s repositoryId=%d type=%s"
                .formatted(stage, repositoryId, cause.getClass().getSimpleName()), cause);
        this.stage = stage;
        this.partial = partial;
    }

    public static ScanStageFailedException at(ScanExecutionState.Stage stage, Long repositoryId,
            Throwable cause) {
        return new ScanStageFailedException(stage, repositoryId, cause, null);
    }

    public static ScanStageFailedException at(ScanExecutionState.Stage stage, Long repositoryId,
            Throwable cause, ScanPipelineResult partial) {
        return new ScanStageFailedException(stage, repositoryId, cause, partial);
    }

    public ScanExecutionState.Stage stage() {
        return stage;
    }

    /** 🔴 클래스 이름만. 메시지를 노출하지 않는다 (S-4). */
    public String failureType() {
        return getCause().getClass().getSimpleName();
    }

    /** 실패 전까지의 성과. 없으면 {@code null}. */
    public ScanPipelineResult partial() {
        return partial;
    }
}
