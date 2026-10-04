--liquibase formatted sql

--changeset manthan:008-dedupe-show-names
-- Shows are about to get a unique name. Keep the oldest show of each name as is and number the later ones, so the
-- unique key below can be added to a database that already has duplicates.
UPDATE shows s JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY name ORDER BY id) AS rn FROM shows) d ON d.id = s.id SET s.name = CONCAT(LEFT(s.name, 240), ' (', d.rn, ')') WHERE d.rn > 1;

--changeset manthan:009-unique-show-name
-- The name column is utf8mb4_0900_ai_ci, so uniqueness ignores case and accents (Friday Night = friday night).
ALTER TABLE shows ADD CONSTRAINT uk_shows_name UNIQUE (name);
