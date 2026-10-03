--liquibase formatted sql

--changeset manthan:005-create-reservation-seats
CREATE TABLE reservation_seats (
    show_id        BIGINT      NOT NULL,
    seat_label     VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    reservation_id CHAR(36)    NOT NULL,
    PRIMARY KEY (show_id, seat_label),
    KEY idx_reservation_seats_reservation (reservation_id),
    CONSTRAINT fk_reservation_seats_seat FOREIGN KEY (show_id, seat_label) REFERENCES seats (show_id, seat_label)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_reservation_seats_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
