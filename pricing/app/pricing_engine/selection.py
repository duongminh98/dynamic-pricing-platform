"""Model selection: champion resolution (Property 22).

None / no parameter -> champion for the line (MISSING_CHAMPION if unconfigured).
There is exactly one serving model (Champion_Model) per line; the runtime
champion-challenger selection has been removed from the refined scope.

Requirements: R12.1, R12.4 (design 6.4).
"""
from __future__ import annotations

from common.errors import ErrorCode, ServiceException
from . import loader

def _champion_model(line: str, algo: str, family: str):
    if family in ("freqsev", "freq_sev"):
        freq = loader.all_artifacts.get(line, {}).get("freq", {}).get(algo) or loader.artifacts.get(line, {}).get("freq")
        sev = loader.all_artifacts.get(line, {}).get("sev", {}).get(algo) or loader.artifacts.get(line, {}).get("sev")
        if freq is None or sev is None:
            return None
        return {"freq": freq, "sev": sev}
    model = loader.all_artifacts.get(line, {}).get(family, {}).get(algo)
    if model is None:
        model = loader.artifacts.get(line, {}).get(family)
    return model

def select_model(line: str) -> dict:
    """Resolve which model to use for a quote.

    Returns a dict with: model_version, algorithm, family, model (object).
    Raises ServiceException on misconfiguration.
    """
    loader.ensure_loaded()
    if line not in loader.LINES:
        raise ServiceException(ErrorCode.UNSUPPORTED_LINE, details={"line": line})

    champ = loader.get_champion(line)
    if not champ or "model_version" not in champ:
        raise ServiceException(ErrorCode.MISSING_CHAMPION, details={"line": line})
    algo = champ.get("algorithm", "lgb")
    family = champ.get("family", "tw")
    model = _champion_model(line, algo, family)
    if model is None:
        raise ServiceException(ErrorCode.MISSING_CHAMPION, details={"line": line})
    return {"model_version": champ["model_version"], "algorithm": algo,
            "family": family, "model": model}