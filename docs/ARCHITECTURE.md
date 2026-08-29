# CricPulse Backend Architecture

## Branch strategy

- `main`: stable, verified releases only
- `develop`: integration and refactoring branch
- `feature/*`: isolated feature work when required

## Target package structure

The project follows a modular feature-first structure:

```
com.cricket.platform
├── config/
├── shared/
│   ├── exception/
│   ├── security/
│   └── websocket/
├── identity/
│   ├── controller/
│   ├── dto/
│   ├── repository/
│   └── service/
├── player/
│   ├── controller/
│   ├── dto/
│   ├── repository/
│   └── service/
├── team/
│   ├── controller/
│   ├── dto/
│   ├── repository/
│   └── service/
├── tournament/
├── match/
└── scoring/
    ├── controller/
    ├── dto/
    ├── domain/
    ├── repository/
    ├── service/
    └── projection/
```

## Layer responsibilities

### Controller
HTTP only:
- request validation
- authentication context
- response status
- delegation to service

Controllers must not contain SQL or scoring business rules.

### DTO
Public API contracts:
- request DTOs
- response DTOs
- command objects

Persistence implementation details must not leak through API contracts.

### Service
Application and business logic:
- authorization decisions
- orchestration
- transactions
- domain rules

### Repository
Persistence only:
- SQL
- row mapping
- CRUD/query operations

### Domain
Pure business concepts and rules where appropriate:
- wicket rules
- strike rotation
- delivery outcome
- innings state

## Refactoring rules

1. Preserve existing API paths unless intentionally versioned.
2. Preserve verified scoring behaviour before structural changes.
3. No SQL inside controllers.
4. Prefer constructor injection.
5. Use records for immutable DTOs.
6. Keep one clear responsibility per class.
7. Move repeated queries into repositories.
8. Add tests before changing sensitive scoring behaviour.
9. Avoid generic `util` dumping grounds.
10. Keep package boundaries aligned with cricket domain modules.

## Refactoring order

1. identity
2. player
3. team
4. tournament
5. match
6. shared/config
7. scoring (last, because it is the most behaviour-sensitive module)

## Scoring protection rule

The scoring engine is production-sensitive. Structural refactoring must not change:
- legal ball calculation
- extras accounting
- wicket attribution
- run-out strike resolution
- over-end strike rotation
- new batter placement
- FOW
- undo behaviour

Every scoring refactor must preserve existing E2E coverage.
