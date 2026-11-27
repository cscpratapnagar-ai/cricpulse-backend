# Local CricPulse backend development

## Requirements

- Docker Desktop running with Linux containers enabled
- Java 25
- Maven 3.9+

## Start PostgreSQL and the API (Windows PowerShell)

From the backend repository root:

```powershell
.\scripts\dev-up.ps1
```

The script starts only the `postgres` service from the root `docker-compose.yml`, waits until `pg_isready` succeeds, and then starts Spring Boot with Maven. The database is available at `localhost:5432` with database `cricket` and user `cricket_app`.

To start only PostgreSQL:

```powershell
.\scripts\dev-up.ps1 -DatabaseOnly
```

Then start the API separately with:

```powershell
mvn -B spring-boot:run
```

## Stop the local database

```powershell
docker compose stop postgres
```

Do not use `docker compose down -v` for routine shutdown: the `cricket_postgres_data` volume contains the local database.

## Database connection configuration

- Local Maven process: `localhost:5432`; default SSL mode is `disable` for the bundled local PostgreSQL container.
- Backend running inside Docker Compose: connect to host `postgres` on port `5432`, not `localhost`.
- Staging/production: the deployment compose files set `DATABASE_URL` to the private Docker-network hostname `postgres`. Credentials come from the server-side deployment `.env`; do not commit that file or paste secrets into Git.
- For a managed PostgreSQL provider, set `DATABASE_URL` to its JDBC URL and include the provider-required TLS option, typically `?sslmode=require`. An explicitly supplied `DATABASE_URL` takes precedence over the local defaults.

## Troubleshooting

```powershell
docker compose ps
docker compose logs --tail 100 postgres
docker compose exec postgres pg_isready -U cricket_app -d cricket
Test-NetConnection localhost -Port 5432
```

If the API still reports connection refused, confirm Docker Desktop is running and that port 5432 is not occupied by another PostgreSQL service. If a different local PostgreSQL instance already owns the port, stop it or change the compose host-port mapping and set `CRICPULSE_PGPORT` to the matching host port.
