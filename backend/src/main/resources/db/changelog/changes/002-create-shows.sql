--liquibase formatted sql

--changeset manthan:002-create-shows
CREATE TABLE shows (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(255) NOT NULL,
    price_paise    BIGINT       NOT NULL,
    per_user_limit INT          NOT NULL DEFAULT 4,
    total_seats    INT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_shows_price CHECK (price_paise >= 0),
    CONSTRAINT chk_shows_per_user_limit CHECK (per_user_limit > 0),
    CONSTRAINT chk_shows_total_seats CHECK (total_seats > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
