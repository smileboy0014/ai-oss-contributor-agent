package com.ossagent.repository.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Duration;
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
     * 이 저장소의 스캔 주기(분). 🔴 <b>{@code null} 이면 전역 기본값</b>을 쓴다 — 완료조건 1.
     *
     * <p>저장소마다 이슈가 쌓이는 속도가 다른데 전역 주기 하나면 활발한 저장소는 늦고
     * 조용한 저장소는 레이트리밋을 태운다.
     *
     * <p>⚠️ {@code 0} 을 「무제한」으로 읽지 않는다 — {@link #isDueForScan} 이 <b>양의 값만</b>
     * 받아들이고 나머지는 기본값으로 떨어뜨린다. {@code 0} 이 통과하면 주기가 사라져
     * 스케줄러가 <b>매 주기마다</b> 그 저장소를 돌린다.
     */
    private Integer scanIntervalMinutes;

    /**
     * 이슈 증분 수집 커서 — 「데이터를 어디까지 봤나」 (#8).
     *
     * <p>🔴 {@link #lastScannedAt} 과 <b>뜻이 다르다.</b> 저쪽은 「우리가 언제 돌렸나」다.
     * 겹쳐 쓰면 스캔이 실패해도 커서가 전진해 <b>이슈를 영구히 건너뛴다.</b>
     *
     * <p>두 값은 항상 함께 움직인다 — ETag 는 {@code since} 와 짝이라
     * 커서가 전진하면 버려야 한다. 그래서 {@link com.ossagent.issue.domain.IssueScanCursor}
     * 로 묶어 다루고, 여기에는 풀어서 저장만 한다.
     */
    private Instant issueCursorUpdatedAt;

    @Column(length = 255)
    private String issueCursorEtag;

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

    /** {@code null} 이면 전역 기본값을 쓴다는 뜻이다 — 값 자체가 없는 것이 정상 상태다. */
    public Integer getScanIntervalMinutes() {
        return scanIntervalMinutes;
    }

    /**
     * 스캔 주기를 바꾼다. {@code null} 을 주면 <b>전역 기본값으로 되돌린다</b>.
     *
     * <p>⚠️ 이 값을 <b>바꾸는 API 는 아직 없다</b> (#26 범위 밖). 지금 이 메서드의 소비자는
     * 테스트뿐이고, 운영에서는 DB 기본값({@code NULL} = 전역 기본)으로만 쓰인다.
     * 그 사실을 적어 두지 않으면 다음 사람이 「설정할 수 있다」고 읽는다.
     */
    public void updateScanInterval(Integer minutes) {
        this.scanIntervalMinutes = minutes;
    }

    /**
     * 지금 스캔할 차례인가 — 정기 스케줄러가 <b>누구를 부를지</b> 고르는 판정 (FR-2).
     *
     * <p>자기 애그리거트 데이터({@link #lastScannedAt}·{@link #scanIntervalMinutes})만으로
     * 답할 수 있으므로 엔티티에 둔다 — {@code architecture.md} §3 결정 트리 Q1.
     *
     * <h2>🔴 한 번도 안 돌았으면 <b>즉시 대상</b>이다</h2>
     *
     * <p>{@code lastScannedAt} 이 {@code null} 일 때 「모르니 건너뛴다」로 두면 새로 등록한
     * 저장소가 <b>영원히 스캔되지 않는다.</b> 주기 필터가 모든 저장소를 걸러 스캔이 영영
     * 안 도는 것이 이 기능의 가장 조용한 고장 모드다.
     *
     * <h2>⚠️ {@code lastScannedAt} 은 「요청 시각」이라 실패해도 전진한다</h2>
     *
     * <p>즉 <b>실패한 스캔도 주기를 소모한다.</b> 결함이 아니라 의도다 — 계속 실패하는
     * 저장소가 매 주기마다 GitHub·LLM 을 태우는 쪽이 더 나쁘다. 「조용히 스캔 안 됨」은
     * {@code scan_execution.phase = FAILED} 와 메트릭에 드러난다.
     *
     * @param defaultInterval {@link #scanIntervalMinutes} 가 없을 때 쓸 전역 기본값
     */
    public boolean isDueForScan(Instant now, Duration defaultInterval) {
        if (now == null || defaultInterval == null) {
            throw new IllegalArgumentException("현재 시각과 기본 주기는 필수다");
        }
        if (lastScannedAt == null) {
            return true;
        }
        Duration interval = scanIntervalMinutes != null && scanIntervalMinutes > 0
                ? Duration.ofMinutes(scanIntervalMinutes)
                : defaultInterval;
        // 🔴 경계는 포함이다. 배타로 잡으면 고정 주기 스케줄러에서 「정확히 주기만큼 지난」
        //    순간이 매번 한 박자씩 밀려 실질 주기가 두 배가 된다
        return !now.isBefore(lastScannedAt.plus(interval));
    }

    public Instant getIssueCursorUpdatedAt() {
        return issueCursorUpdatedAt;
    }

    public String getIssueCursorEtag() {
        return issueCursorEtag;
    }

    /**
     * 이슈 증분 수집 커서를 갱신한다. 🔴 <b>저장이 끝난 뒤에만 부른다</b> — #8.
     *
     * <p>조회 직후에 전진시키면 저장에 실패했을 때 그 구간을 영영 다시 읽지 않는다.
     * 순서가 곧 안전 성질이다.
     *
     * <p>두 값을 <b>함께</b> 받는 이유는 ETag 가 {@code since} 와 짝이기 때문이다 —
     * 따로 세터를 두면 한쪽만 갱신돼 짝이 어긋난다.
     *
     * <p>⚠ 원시값으로 받는다. 커서를 묶은 값 타입({@code IssueScanCursor})은
     * {@code issue} 도메인의 것이고, 이 애그리거트가 <b>남의 도메인 타입을 import 하지
     * 않는다</b> — architecture 규율 ④. 조립은 {@code issue} 쪽에서 한다.
     */
    public void updateIssueScanCursor(Instant updatedSince, String etag) {
        this.issueCursorUpdatedAt = updatedSince;
        this.issueCursorEtag = etag;
    }
}
