-- Public registration creates PLAYER accounts. Ensure existing PLAYER accounts
-- also have the registered player profile required by team membership.
INSERT INTO players (id, user_id)
SELECT gen_random_uuid(), u.id
FROM users u
WHERE UPPER(u.role) = 'PLAYER'
  AND NOT EXISTS (
      SELECT 1
      FROM players p
      WHERE p.user_id = u.id
  );
