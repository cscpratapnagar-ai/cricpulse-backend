-- V16 - Reliable live partnership tracking
-- Ensure every innings has a current partnership before the first delivery
-- and keep existing LIVE innings resumable.

CREATE OR REPLACE FUNCTION ensure_current_partnership()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_exists BOOLEAN;
    current_wicket_number INTEGER;
BEGIN
    SELECT EXISTS (
        SELECT 1
        FROM partnerships
        WHERE innings_id = NEW.innings_id
          AND is_current = TRUE
    ) INTO current_exists;

    IF NOT current_exists THEN
        SELECT COALESCE(wickets, 0)
        INTO current_wicket_number
        FROM innings
        WHERE id = NEW.innings_id;

        INSERT INTO partnerships (
            innings_id,
            wicket_number,
            batter_one_id,
            batter_two_id,
            runs,
            balls,
            is_current
        )
        VALUES (
            NEW.innings_id,
            current_wicket_number,
            NEW.striker_id,
            NEW.non_striker_id,
            0,
            0,
            TRUE
        )
        ON CONFLICT (innings_id, wicket_number)
        DO UPDATE SET
            batter_one_id = EXCLUDED.batter_one_id,
            batter_two_id = EXCLUDED.batter_two_id,
            is_current = TRUE;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_ensure_current_partnership ON deliveries;

CREATE TRIGGER trg_ensure_current_partnership
BEFORE INSERT ON deliveries
FOR EACH ROW
EXECUTE FUNCTION ensure_current_partnership();

-- Backfill current partnerships for LIVE innings created before this projection existed.
INSERT INTO partnerships (
    innings_id,
    wicket_number,
    batter_one_id,
    batter_two_id,
    runs,
    balls,
    is_current
)
SELECT
    i.id,
    i.wickets,
    i.striker_id,
    i.non_striker_id,
    COALESCE(SUM(d.total_runs), 0),
    COALESCE(SUM(CASE WHEN d.legal_delivery THEN 1 ELSE 0 END), 0),
    TRUE
FROM innings i
LEFT JOIN deliveries d
    ON d.innings_id = i.id
   AND d.sequence_number > COALESCE((
       SELECT MAX(dw.sequence_number)
       FROM deliveries dw
       WHERE dw.innings_id = i.id
         AND dw.wicket_type IS NOT NULL
   ), 0)
WHERE i.status = 'LIVE'
  AND i.striker_id IS NOT NULL
  AND i.non_striker_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM partnerships p
      WHERE p.innings_id = i.id
        AND p.is_current = TRUE
  )
GROUP BY i.id, i.wickets, i.striker_id, i.non_striker_id
ON CONFLICT (innings_id, wicket_number)
DO UPDATE SET
    batter_one_id = EXCLUDED.batter_one_id,
    batter_two_id = EXCLUDED.batter_two_id,
    runs = EXCLUDED.runs,
    balls = EXCLUDED.balls,
    is_current = TRUE;
