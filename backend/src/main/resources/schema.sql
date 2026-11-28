-- User accounts (specs/002-user-auth, data-model.md "User"). The only durable data in the MVP.
-- Created on start-up with IF NOT EXISTS, so the statement is safe to run on every start.
CREATE TABLE IF NOT EXISTS users (
    id            UUID                     PRIMARY KEY,
    email         VARCHAR(254)             NOT NULL UNIQUE,
    password_hash VARCHAR(255)             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
