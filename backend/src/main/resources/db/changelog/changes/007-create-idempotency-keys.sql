--liquibase formatted sql

--changeset manthan:007-create-idempotency-keys
CREATE TABLE idempotency_keys (
    user_id        VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    idem_key       VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    request_hash   CHAR(64)     CHARACTER SET ascii NOT NULL,
    reservation_id CHAR(36)     NOT NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, idem_key),
    KEY idx_idempotency_keys_reservation (reservation_id),
    CONSTRAINT fk_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_idempotency_keys_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
