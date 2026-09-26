# MAAskullgirls contributor guide

Purpose: project-specific constraints and a reading map for coding agents.
Current architecture lives in `PF_BOT.md`; usage lives in `README.md`.

## Read only what the task needs

| Task | Code first | Relevant reference |
|---|---|---|
| Setup, native libraries, emulator connection | `tools/pf_native.py`, `tools/pf_env.py` | `README.md`; `PF_BOT.md` §1 |
| Battle loop, energy, opponent selection | `tools/pf_bot.py`, `tools/pf_vision.py` | `PF_BOT.md` §§3–6 |
| Pure scoring/configuration rules | `tools/pf_domain.py` | `PF_BOT.md` §6.5 |
| Persistence, session history, JJC snapshots | `tools/pf_storage.py`, `tools/pf_store.py`, `tools/jjc_store.py` | `PF_BOT.md` §§6.5a, 6.11 |
| Navigation, scheduling, process lifecycle | `tools/pf_scene.py`, `tools/pf_schedule.py`, `tools/pf_tray.py` | `PF_BOT.md` §§6.9–6.10, 8 |
| WebUI and API | `tools/pf_webui.py`, `tools/static/webui.*`, `tools/static/themes.*` | `README.md`; `PF_BOT.md` §6.7 |
| Daily task inventory | `tools/static/webui.js` | `docs/explore/2026-09-05/REPORT.md` (historical evidence) |
| Previous all-buttons-dead failure | UI scripts | `docs/incident-2026-09-24-webui-js-dead.md` (historical evidence) |
| Phone AutoJS scripts | `phone/autojs/` | `phone/autojs/README.md` |

Use headings/search to select sections of the long design manual. Do not read all
docs or historical screenshot reports automatically. Icon `ATTRIBUTION.md` is
provenance, not operating instructions.

## Boundaries to preserve

- Native callers must run `pf_native.preload_msvcrt()` before the first cv2/MAA
  import. `pf_env.preload_msvcrt` remains a compatibility export.
- `pf_domain.py` must stay independent of MAA, cv2, runtime state, files and HTTP.
  The old domain names remain importable from `pf_store` for existing callers.
- Use `config.json`/`resolve_adb()` for device paths and addresses; do not introduce
  a second hardcoded ADB serial. Keep UTF-8 subprocess decoding.
- Preserve CPU OCR, calibrated recognition thresholds, ROI semantics and priority
  ordering unless the task specifically requires changing them with evidence.
- Stop MuMu through MuMuManager; retain WMI detached startup and the HTTP service
  identity check. Scheduler serialization is process-local, not a cross-process lease.
- Preserve current CLI entry points, HTTP routes and JSON/CSV formats when refactoring.
  Importing the runtime facade `pf_store` still initializes `STORE`: use `pf_domain`
  for pure logic, or `pf_storage.ScoreStore` with explicit dependencies for isolated
  persistence tooling. Do not import the runtime facade for a pure utility.
- Keep the real bot's access gate. JJC refresh must remain explicit; ordinary UI
  reads must not fetch the external schedule.
- Preview data is simulated. Do not treat successful previews as emulator validation.
  Respect the user's requested validation scope; report checks actually performed.

## Markdown ownership

Update README for usage and `PF_BOT.md` for architecture/operations. Update this
reading map when ownership changes. Keep incident/exploration reports as dated
evidence and preserve attribution. Do not add a per-task summary/report or a
second design manual; use the commit/PR for the change summary.
