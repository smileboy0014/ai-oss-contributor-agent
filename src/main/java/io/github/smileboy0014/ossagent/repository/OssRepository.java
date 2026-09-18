package io.github.smileboy0014.ossagent.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

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

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getUrl() { return url; }
    public boolean isEnabled() { return enabled; }
    public Instant getLastScannedAt() { return lastScannedAt; }
    public void markScanned() { this.lastScannedAt = Instant.now(); }
}
