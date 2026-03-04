-- Initial schema: migration job state and audit tables
-- Flyway owns schema management (spring.jpa.hibernate.ddl-auto=none)

CREATE TABLE IF NOT EXISTS migration_job (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_key       VARCHAR(255) NOT NULL UNIQUE,
    status        VARCHAR(50)  NOT NULL,
    started_at    DATETIME     NOT NULL,
    completed_at  DATETIME,
    error_message TEXT,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS migration_audit (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id       BIGINT       NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    event_detail TEXT,
    occurred_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (job_id) REFERENCES migration_job(id)
);
