package com.ossagent.repository.application;

import com.ossagent.repository.adapter.out.persistence.OssRepositoryRepository;
import com.ossagent.repository.domain.OssRepository;
import com.ossagent.repository.domain.RepositoryNotFoundException;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이슈 증분 수집 커서를 읽고 쓴다 — {@code issue} 도메인이 {@code repository} 애그리거트에
 * 접근하는 <b>유일한 통로</b> (#8).
 *
 * <p>커서는 {@code oss_repository} 에 저장되는데, {@code issue} 가 그 엔티티를 직접
 * import 하면 애그리거트를 넘는 참조가 된다 — architecture 규율 ④. 그래서 UseCase 를 둔다.
 *
 * <p>주고받는 것은 <b>원시값</b>이다. 커서를 묶은 값 타입은 {@code issue} 도메인의 것이라
 * 여기서 알 필요가 없고, 알면 의존이 양방향이 된다.
 *
 * <p>🔴 트랜잭션이 <b>짧다.</b> 스캔 전체를 감싸지 않는다 — 대외 호출이 그 안에 들어가면
 * 커넥션이 GitHub 응답을 기다리는 동안 잡힌다({@code architecture.md} §2).
 */
@Service
public class IssueScanCursorUseCase {

    private final OssRepositoryRepository repositories;

    public IssueScanCursorUseCase(OssRepositoryRepository repositories) {
        this.repositories = repositories;
    }

    /** 저장된 {@code since} 값. 한 번도 수집하지 않았으면 {@code null}. */
    @Transactional(readOnly = true)
    public Instant cursorUpdatedAt(Long repositoryId) {
        return load(repositoryId).getIssueCursorUpdatedAt();
    }

    /** 저장된 ETag(page 1 응답의 것). 없으면 {@code null}. */
    @Transactional(readOnly = true)
    public String cursorEtag(Long repositoryId) {
        return load(repositoryId).getIssueCursorEtag();
    }

    /**
     * 커서를 갱신한다. 🔴 <b>이슈 저장이 끝난 뒤에만 부른다.</b>
     *
     * <p>두 값을 함께 받는다 — ETag 는 {@code since} 와 짝이라 따로 갱신하면 어긋난다.
     */
    @Transactional
    public void updateCursor(Long repositoryId, Instant updatedSince, String etag) {
        load(repositoryId).updateIssueScanCursor(updatedSince, etag);
    }

    private OssRepository load(Long repositoryId) {
        return repositories.findById(repositoryId)
                .orElseThrow(() -> new RepositoryNotFoundException(repositoryId));
    }
}
