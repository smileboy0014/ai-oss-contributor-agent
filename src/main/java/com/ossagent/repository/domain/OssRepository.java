package com.ossagent.repository.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 에이전트가 기여 대상으로 등록한 외부 OSS 저장소.
 *
 * <p>이 엔티티가 가리키는 것은 <b>대상 저장소</b>이지 이 프로젝트 자신이 아니다.
 * 대상 저장소에 대한 쓰기 경로는 사용자 Fork 로만 열린다 —
 * {@code .claude/rules/context/safety-boundaries.md} S-1.
 */
@Entity
@Table(name = "oss_repository")
public class OssRepository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String url;

    @Column(nullable = false)
    private boolean enabled = true;

    private Instant lastScannedAt;

    /**
     * 이 저장소의 기여 규약. 아직 분석되지 않았으면 {@code null} 이다 — S-5.
     *
     * <p>같은 {@code repository} 도메인 안이라 연관관계를 쓴다. 규율 ④ 가 막는 것은
     * <b>도메인을 넘는</b> 참조다.
     *
     * <p>{@code mappedBy} 로 <b>읽기 전용 역방향</b>이다. FK 는 {@code repository_policy}
     * 쪽에 있고, 여기서 넣고 빼는 것으로 관계가 바뀌지 않는다.
     *
     * <p>⚠️ {@code cascade} 를 걸지 않는다. 저장소를 지운다고 규약이 따라 지워지면
     * 「무엇을 왜 배제했는가」의 근거가 사라진다 — 이 프로젝트는 <b>종단 기록을 지우지 않는다</b>.
     */
    @OneToOne(mappedBy = "repository", fetch = FetchType.LAZY)
    private RepositoryPolicy policy;

    protected OssRepository() {
    }

    public OssRepository(String owner, String name, String url) {
        this.owner = owner;
        this.name = name;
        this.url = url;
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public RepositoryPolicy getPolicy() {
        return policy;
    }

    /** 기여 규약이 아직 분석되지 않았다 — 구현 단계로 넘어갈 수 없다 (S-5). */
    public boolean hasNoPolicy() {
        return policy == null;
    }

    public Instant getLastScannedAt() {
        return lastScannedAt;
    }

    public void markScanned(Instant scannedAt) {
        this.lastScannedAt = scannedAt;
    }
}
