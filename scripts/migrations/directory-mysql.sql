-- Additive migration: existing tables and rows are preserved.
CREATE TABLE IF NOT EXISTS directory_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    school VARCHAR(120),
    student_id VARCHAR(40),
    github_username VARCHAR(39),
    account_type ENUM('STUDENT', 'GUEST') NOT NULL,
    created_at DATETIME(6) NOT NULL
);
