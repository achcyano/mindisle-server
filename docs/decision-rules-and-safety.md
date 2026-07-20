# Decision rules and safety protocol (prototype)

## Risk tiers

| Level | Triggers | System behavior |
|-------|----------|-----------------|
| HIGH | Suicide item / NLP risk / med SEVERE / PHQ-9≥20 | Crisis message, SAFETY task, alert doctor, stop auto JITAI |
| MEDIUM | Any dim MODERATE/SEVERE (non-crisis), PHQ-9≥10, deterioration flags | Match 1–2 modules, doctor weekly review |
| LOW | Mild/none or unobserved | Routine matching only when signals appear |

## Deterioration flags

- Mood drop ≥2 on each of last 3 EMA days
- EMA response rate < 0.35 over 7d (with some data)
- NLP polarity slope ≤ -0.15 over 7d

## Module priority (lower number first)

1. Medication communication  
2. Anxiety breathing / PMR  
3. Behavioral activation (low mood / activity / social)  
4. Mindfulness (rumination)  
5. Sleep hygiene  

Hard safety rules are never weakened by learned weights.

## Doctor alert workflow

OPEN → ACKED (note required) → RESOLVED (optional).  
Default list shows OPEN only.
