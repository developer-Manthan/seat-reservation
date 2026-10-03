--liquibase formatted sql

--changeset manthan:004-create-seats
CREATE TABLE seats (
    show_id    BIGINT      NOT NULL,
    seat_label VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    status     ENUM('available','held','confirmed') NOT NULL DEFAULT 'available',
    PRIMARY KEY (show_id, seat_label),
    KEY idx_seats_show_status (show_id, status),
    CONSTRAINT fk_seats_show FOREIGN KEY (show_id) REFERENCES shows (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
