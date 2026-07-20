# Adaptive Intervention API Overview

## Closed loop

`data -> features -> state -> risk -> decision -> match -> feedback`

## Patient endpoints

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/v1/users/me/ema` | Submit EMA; recomputes state; auto-matches intervention unless HIGH |
| GET | `/api/v1/users/me/ema/today` | Completed/pending slots |
| GET | `/api/v1/users/me/state/current` | Latest snapshot (stale >6h refreshes) |
| POST | `/api/v1/users/me/state/recompute` | Force recompute |
| GET | `/api/v1/users/me/tasks` | Pending UI tasks |
| POST | `/api/v1/users/me/tasks/{id}/done` | Mark task done |
| POST | `/api/v1/users/me/analytics/events` | App usage events (`payload_json`) |
| GET/POST | `/api/v1/users/me/interventions/*` | Modules, start, pending, feedback |
| POST | `/api/v1/users/me/doses/check-in` | Dose check-in (must match schedule) |

## Doctor adaptive endpoints

| Method | Path | Notes |
|--------|------|-------|
| GET | `/api/v1/doctors/me/alerts` | Open safety alerts |
| POST | `/api/v1/doctors/me/alerts/{id}/ack` | Ack/resolve with note |
| GET | `/api/v1/doctors/me/patients/{id}/state` | Seven-dim state + features |
| GET | `/api/v1/doctors/me/patients/{id}/nlp-summary` | NLP window summary |
| GET | `/api/v1/doctors/me/patients/{id}/conversations/export` | Chat export (`truncated`, NLP by messageId) |

Patient list `metrics` now includes: scale totals, `adherence`, `riskLevel`, `openAlertCount`, `emaCompletionRate7d`.

## Research endpoints (doctor JWT)

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/v1/research/enrollments` | Consent + PHQ-9 5–14 gate |
| POST | `/api/v1/research/enrollments/{id}/randomize` | 1:1 arm assignment placeholder |
| POST | `/api/v1/research/enrollments/{id}/visits` | Visit instrument pack |
| POST | `/api/v1/research/enrollments/{id}/ae` | AE/SAE record (SAE stamps ethics time) |
| POST | `/api/v1/research/enrollments/{id}/qc` | 10% QC review sample entry |
| GET | `/api/v1/research/export` | Enrollment export rows |

## Safety rules

- PHQ-9 `SUICIDE_RISK` and NLP crisis keywords escalate HIGH alerts.
- HIGH risk: no auto intervention match; pending INTERVENTION tasks dismissed; SAFETY task created.
- Alerts dedupe within 24h; orphan alerts backfilled on doctor bind.
- Symptom SEVERE alone (e.g. sleep) is MEDIUM, not crisis HIGH.
- Med safety SEVERE / PHQ-9 ≥ 20 / suicide flags remain HIGH.

## Feature keys (state.features)

`moodAvg7d`, `moodVar7d`, `moodSlope7d`, `sleepAvg7d`, `sleepSlope7d`, `emaResponseRate7d`, `moodDrop3d`, `deteriorating`, `phq9`, `phq9DeltaPrev`, `gad7`, `gad7DeltaPrev`, `nlpRiskHits7d`, `polaritySlope7d`, `appUsageEvents7d`, `observed`.

## Intervention feedback

- First feedback adjusts match weights; repeats are no-ops.
- Auto match budget: ≤2 non-USER deliveries/day; same module 4h cooldown.
