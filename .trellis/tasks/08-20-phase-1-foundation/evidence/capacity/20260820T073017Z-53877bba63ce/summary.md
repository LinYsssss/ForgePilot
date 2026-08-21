# Phase 1 capacity result

**FAIL**

- Baseline: 21 samples, 355 seconds.
- Steady: 17 samples, 318 seconds.
- MemAvailable min/median/final: 2754060 / 2765012 / 2754060 KiB.
- Swap used baseline median / steady max / final: 22000 / 22000 / 22000 KiB.
- Health samples: 11; restart delta: {'forgepilot-phase1-cap-20260820t073017z-53877bba63ce-postgres-1': 0, 'forgepilot-phase1-cap-20260820t073017z-53877bba63ce-backend-1': 0, 'forgepilot-phase1-cap-20260820t073017z-53877bba63ce-frontend-1': 0}.
- Docker bad events: 0; kernel OOM markers: False.

## Checks

- [x] baseline_duration
- [x] steady_duration
- [x] steady_samples
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
