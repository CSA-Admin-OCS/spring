-- Additive migration: existing tables and rows are preserved.
CREATE TABLE IF NOT EXISTS directory_accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL,
    school VARCHAR(120),
    student_id VARCHAR(40),
    github_username VARCHAR(39),
    account_type VARCHAR(255) NOT NULL CHECK (account_type IN ('STUDENT', 'GUEST')),
    created_at TIMESTAMP NOT NULL
);
