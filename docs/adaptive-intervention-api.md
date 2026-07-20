# Adaptive Intervention APIs

## Patient

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/users/me/ema` | Submit EMA |
| GET | `/api/v1/users/me/ema/today` | Today EMA slots |
| GET | `/api/v1/users/me/state/current` | Seven-dimension state |
| POST | `/api/v1/users/me/state/recompute` | Recompute state |
| GET | `/api/v1/users/me/tasks` | Pending UI tasks |
| GET | `/api/v1/users/me/medications/today-doses` | Today dose plan |
| POST | `/api/v1/users/me/medications/dose-checkins` | Dose check-in |
| GET | `/api/v1/users/me/interventions/modules` | Module catalog |
| POST | `/api/v1/users/me/interventions/start` | Start module |
| POST | `/api/v1/users/me/interventions/match` | Match by state |
| POST | `/api/v1/users/me/interventions/{id}/feedback` | Feedback |

## AI SSE extra events

- `tool_call` / `tool_result` / `ui_action`
- UI action types: `OPEN_SCALE`, `OPEN_INTERVENTION`, `OPEN_EMA`, `OPEN_SIDE_EFFECT`, `OPEN_SAFETY`

## Doctor

| Method | Path |
|--------|------|
| GET | `/api/v1/doctors/me/alerts` |
| POST | `/api/v1/doctors/me/alerts/{id}/ack` |
| GET | `/api/v1/doctors/me/patients/{id}/state` |
| GET | `/api/v1/doctors/me/patients/{id}/nlp-summary` |
| GET | `/api/v1/doctors/me/patients/{id}/conversations/export` |

## LLM models

- `LLM_MODEL_FLASH=deepseek-v4-flash` daily chat/tools/NLP
- `LLM_MODEL_PRO=deepseek-v4-pro` assessment / crisis review
