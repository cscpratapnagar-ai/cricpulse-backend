-- Keep innings lifecycle authoritative in PostgreSQL so every scoring path
-- (UI, API, resume, public viewer) sees the same completed state.

CREATE OR REPLACE FUNCTION cricpulse_complete_innings_if_needed()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'LIVE' THEN
        IF NEW.wickets >= 10
           OR (NEW.total_overs IS NOT NULL AND NEW.legal_balls >= NEW.total_overs * 6)
           OR (NEW.target_runs IS NOT NULL AND NEW.total_runs >= NEW.target_runs) THEN
            NEW.status := 'COMPLETED';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_innings_lifecycle_completion ON innings;

CREATE TRIGGER trg_innings_lifecycle_completion
BEFORE UPDATE OF total_runs, wickets, legal_balls, status ON innings
FOR EACH ROW
EXECUTE FUNCTION cricpulse_complete_innings_if_needed();

-- Repair any already-finished innings that were left LIVE by earlier scoring code.
UPDATE innings
SET status = 'COMPLETED'
WHERE status = 'LIVE'
  AND (
      wickets >= 10
      OR (total_overs IS NOT NULL AND legal_balls >= total_overs * 6)
      OR (target_runs IS NOT NULL AND total_runs >= target_runs)
  );
