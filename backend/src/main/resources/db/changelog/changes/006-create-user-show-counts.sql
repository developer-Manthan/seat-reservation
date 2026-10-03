--liquibase formatted sql

--changeset manthan:006-create-user-show-counts
CREATE TABLE user_show_counts (
    user_id    VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    show_id    BIGINT      NOT NULL,
    held_count INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, show_id),
    KEY idx_user_show_counts_show (show_id),
    CONSTRAINT fk_user_show_counts_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_user_show_counts_show FOREIGN KEY (show_id) REFERENCES shows (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_user_show_counts_held CHECK (held_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
