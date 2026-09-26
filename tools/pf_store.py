"""PF runtime storage facade; importing it initializes the shared STORE.

Existing callers keep their imports here. Isolated tooling can import pf_domain
or construct pf_storage.ScoreStore with explicit dependencies instead.
"""
from pathlib import Path

from pf_env import STATE
from jjc_store import JJC, VERSIONS
from pf_domain import (UNSET, ScoreTracker, clean_energy, clean_rest,
                       clean_rule, clean_target)
from pf_storage import HISTORY_CAP, ScoreStore as _ScoreStore

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = PROJECT_ROOT / "debug" / "pf"
SESSIONS_PATH = DATA_DIR / "sessions.json"
SCORE_CSV = DATA_DIR / "score_log.csv"


def _jjc_ref():
    """Current or latest cached JJC reference; never fetches from the network."""
    snap = JJC.peek()
    return JJC.brief(snap) if snap else None


class ScoreStore(_ScoreStore):
    """Session/history store with the application's existing runtime defaults."""

    def __init__(self, *, data_dir=None, logger=None, version_ledger=None,
                 jjc_ref=None) -> None:
        super().__init__(
            data_dir=DATA_DIR if data_dir is None else data_dir,
            logger=STATE.log if logger is None else logger,
            version_ledger=VERSIONS if version_ledger is None else version_ledger,
            jjc_ref=_jjc_ref if jjc_ref is None else jjc_ref,
        )


STORE = ScoreStore()
