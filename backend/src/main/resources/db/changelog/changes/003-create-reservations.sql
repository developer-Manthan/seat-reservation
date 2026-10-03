--liquibase formatted sql

--changeset manthan:003-create-reservations
CREATE TABLE reservations (
    id           CHAR(36)    NOT NULL,
    show_id      BIGINT      NOT NULL,
    user_id      VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    amount_paise BIGINT      NOT NULL DEFAULT 0,
    status       ENUM('pending','confirmed','cancelled') NOT NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_reservations_show (show_id),
    KEY idx_reservations_user (user_id),
    CONSTRAINT fk_reservations_show FOREIGN KEY (show_id) REFERENCES shows (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reservations_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_reservations_amount CHECK (amount_paise >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
