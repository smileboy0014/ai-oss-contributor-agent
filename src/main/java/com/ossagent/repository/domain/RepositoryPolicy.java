package com.ossagent.repository.domain;

import com.ossagent.support.ExternalText;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대상 저장소의 기여 규약. 이것 없이 구현 단계로 넘어가지 않는다 — S-5.
 *
 * <p>우리 커밋·PR 컨벤션은 이 저장소 안에서만 유효하다. 대상 저장소에 나가는 산출물은
 * 여기 담긴 규약을 따른다.
 */
@Entity
@Table(name = "repository_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepositoryPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 소유 저장소. <b>같은 {@code repository} 도메인 안이라 연관관계를 쓴다</b> — architecture.md 규율 ④.
     *
     * <p>규율 ④가 막는 것은 <b>도메인을 넘는</b> 참조다. 남의 도메인 엔티티를 import 하면
     * 컴파일 의존이 생겨 떼어낼 때 코드를 고쳐야 한다. 같은 도메인 안에서는 그 문제가 없고,
     * 오히려 값으로 들고 있으면 정책을 읽을 때마다 저장소를 따로 조회해야 한다.
     *
     * <p>물리 FK 는 마이그레이션에 있다. JPA 애노테이션이 아니라 <b>SQL 이 제약을 결정한다</b> —
     * {@code ddl-auto: validate} 라 {@code @ForeignKey(NO_CONSTRAINT)} 같은 지정은 무시된다.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private OssRepository repository;

    private String javaVersion;

    private String buildCommand;

    private String testCommand;

    @Column(nullable = false)
    private boolean issueReferenceRequired;

    @Column(nullable = false)
    private boolean signoffRequired;

    @Column(nullable = false)
    private boolean testsRequired;

    /**
     * AI 기여 허용 여부. <b>{@code Boolean} 이고 NULL 을 허용한다</b> — S-5.
     *
     * <p>NULL = 판정 실패 = <b>보류</b>(허용 아님). {@code boolean} 원시타입으로 바꾸면
     * 판정 실패가 {@code false} 로 뭉개지고, {@code NOT NULL DEFAULT TRUE} 로 두면
     * 「AI 기여 금지」 저장소를 기본 허용해 버린다. 어느 쪽이든 기본값이 곧 위반이 된다.
     */
    private Boolean aiContributionAllowed;

    @ExternalText(ExternalText.Source.TARGET_REPOSITORY)
    @Column(columnDefinition = "TEXT")
    private String contributionRules;

    /** 규약은 바뀐다. 재분석 주기 판단의 근거. */
    private Instant analyzedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** 판정이 서지 않았는가. NULL 은 「허용」이 아니라 「보류」다 — S-5. */
    public boolean isAiContributionUndetermined() {
        return aiContributionAllowed == null;
    }
}
