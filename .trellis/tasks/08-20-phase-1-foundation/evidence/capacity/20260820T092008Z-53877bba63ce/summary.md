# Phase 1 capacity result

**FAIL**

- Baseline: 21 samples, 300 seconds.
- Steady: 17 samples, 244 seconds.
- Sample cadence min/median/max: baseline 15 / 15.0 / 15 seconds; steady 15 / 15.0 / 15 seconds.
- MemAvailable min/median/final: 2684212 / 2692276 / 2690876 KiB.
- Swap used baseline median / steady max / final: 20208 / 20976 / 20976 KiB.
- Health samples: 11; restart delta: {'forgepilot-phase1-cap-20260820t092008z-53877bba63ce-postgres-1': 0, 'forgepilot-phase1-cap-20260820t092008z-53877bba63ce-backend-1': 0, 'forgepilot-phase1-cap-20260820t092008z-53877bba63ce-frontend-1': 0}.
- Docker bad events: 0; kernel OOM markers: False.

## Checks

- [x] baseline_duration
- [x] baseline_cadence
- [x] steady_duration
- [x] steady_samples
- [x] steady_cadence
- [x] mem_available
- [x] health
- [x] restarts
- [x] oom_killed
- [x] existing_services
- [x] docker_events
- [x] kernel_oom
- [x] swap_growth
- [ ] swap_activity
- [x] container_headroom
- [x] postgres_version
- [x] pgvector
- [x] flyway
- [x] empty_schema

Synthetic/reference evaluation was not run during this capacity window.
