package com.ossagent.repository.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "oss_repositories")
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

    public Instant getLastScannedAt() {
        return lastScannedAt;
    }

    public void markScanned(Instant scannedAt) {
        this.lastScannedAt = scannedAt;
    }
}
