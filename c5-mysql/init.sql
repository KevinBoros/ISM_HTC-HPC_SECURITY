USE bmpdb;

CREATE TABLE IF NOT EXISTS jobs (
    job_id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    operation VARCHAR(20),
    original_filename VARCHAR(255),
    result_filename VARCHAR(255),
    content_type VARCHAR(100),
    result_blob LONGBLOB,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);