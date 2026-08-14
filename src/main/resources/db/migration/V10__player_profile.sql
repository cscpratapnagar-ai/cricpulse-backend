ALTER TABLE players
    ADD COLUMN date_of_birth DATE,
    ADD COLUMN city VARCHAR(120),
    ADD COLUMN playing_role VARCHAR(30),
    ADD COLUMN jersey_number INTEGER,
    ADD COLUMN bio VARCHAR(500),
    ADD COLUMN profile_photo_url VARCHAR(500);

ALTER TABLE players
    ADD CONSTRAINT players_jersey_number_chk
    CHECK (jersey_number IS NULL OR jersey_number BETWEEN 0 AND 99);
