# CricPulse Backend Refactor Plan

## Goal
Refactor the backend incrementally without breaking the stable scoring engine.

## Target Package Rule
Use feature-based modules. Each module owns its controller, service, service implementation, repository, DTOs, entities, and mappers.

```
com.cricpulse
├── common
│   ├── exception
│   ├── response
│   ├── util
│   └── validation
├── config
├── auth
├── player
├── team
├── match
├── innings
├── scoring
│   ├── controller
│   ├── dto
│   │   ├── request
│   │   └── response
│   ├── service
│   │   └── impl
│   ├── engine
│   ├── calculator
│   ├── validator
│   ├── handler
│   ├── repository
│   ├── entity
│   └── mapper
├── tournament
├── statistics
└── realtime
```

## Layer Responsibilities
- Controller: HTTP request/response only.
- DTO: API contracts only.
- Service: use-case orchestration and transactions.
- Service Impl: service implementation.
- Repository: persistence access only.
- Entity: persistence model only.
- Mapper: entity/domain/DTO conversion.
- Validator: business validation.
- Calculator: deterministic cricket calculations.
- Handler: specialised scoring/wicket behaviour.
- Engine: coordinates scoring rules.

## Safety Rules
1. Do not rewrite working scoring logic blindly.
2. Preserve existing API behaviour unless intentionally versioned.
3. Move one module at a time.
4. Compile and run existing tests after every refactor step.
5. Main remains stable; all refactor work happens on develop.
6. No controller should contain business logic.
7. No repository should contain orchestration logic.

## Refactor Order
1. Audit current source tree.
2. Establish common/config conventions.
3. Refactor low-risk modules: auth, player, team.
4. Refactor match and innings.
5. Refactor tournament/statistics/realtime.
6. Refactor scoring last, protected by the existing E2E matrix.

## Current First Task
Create an exact current-to-target file mapping before moving production classes. No functional scoring change is allowed in the structural phase.
