-- Test data cho authentication system

-- Insert test user (manual registration)
INSERT INTO users (id, email, name, password_hash, provider, role, email_verified, balance, created_at, updated_at) VALUES
('test-user-001', 'test@example.com', 'Test User', '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqfuWDZLiEsnbXqvQf6jW4q', 'manual', 'user', true, 100.00, NOW(), NOW());
-- Password is: password123

-- Insert admin user
INSERT INTO users (id, email, name, password_hash, provider, role, email_verified, balance, created_at, updated_at) VALUES
('admin-user-001', 'admin@example.com', 'Admin User', '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqfuWDZLiEsnbXqvQf6jW4q', 'manual', 'admin', true, 1000.00, NOW(), NOW());
-- Password is: password123

-- Insert Google OAuth user example
INSERT INTO users (id, email, name, google_id, provider, role, email_verified, balance, avatar, created_at, updated_at) VALUES
('google-user-001', 'googleuser@gmail.com', 'Google User', 'google-sub-id-123456789', 'google', 'user', true, 50.00, 'https://lh3.googleusercontent.com/example-avatar', NOW(), NOW());

-- Clean up expired password reset tokens (example maintenance query)
-- DELETE FROM password_resets WHERE expires_at < NOW(); 