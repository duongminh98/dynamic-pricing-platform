"""Model selection: champion / challenger resolution (Property 22).

None        -> champion for the line (MISSING_CHAMPION if unconfigured).
challenger  -> use it only if configured for the line; otherwise
               CHALLENGER_NOT_CONFIGURED (never silently fall back to champion).

Requirements: R12.1, R12.3, R12.5, R12.6 (design 6.4).
"""
from __future__ import annotations

from common.errors import ErrorCode, ServiceException
from . import loader


def _champion_model(line: str, algo: str, family: str):
    model = loader.all_artifacts.get(line, {}).get(family, {}).get(algo)
    if model is None:
        model = loader.artifacts.get(line, {}).get(family)
    return model


def select_model(line: str, requested_model: str | None) -> dict:
    """Resolve which model to use for a quote.

    Returns a dict with: model_version, algorithm, family, model (object).
    Raises ServiceException on misconfiguration.
    """
    loader.ensure_loaded()
    if line not in loader.LINES:
        raise ServiceException(ErrorCode.UNSUPPORTED_LINE, details={"line": line})

    champ = loader.get_champion(line)
    if requested_model is None:
        if not champ or "model_version" not in champ:
            raise ServiceException(ErrorCode.MISSING_CHAMPION, details={"line": line})
        algo = champ.get("algorithm", "lgb")
        family = champ.get("family", "tw")
        model = _champion_model(line, algo, family)
        if model is None:
            raise ServiceException(ErrorCode.MISSING_CHAMPION, details={"line": line})
        return {"model_version": champ["model_version"], "algorithm": algo,
                "family": family, "model": model}

    # A challenger is requested: it must be explicitly configured for the line.
    challengers = champ.get("challengers", []) if champ else []
    match = next((c for c in challengers if c.get("model_version") == requested_model), None)
    if match is None:
        raise ServiceException(ErrorCode.CHALLENGER_NOT_CONFIGURED,
                               details={"line": line, "model_version": requested_model})
    algo = match.get("algorithm", "lgb")
    family = match.get("family", "tw")
    model = _champion_model(line, algo, family)
    if model is None:
        raise ServiceException(ErrorCode.CHALLENGER_NOT_CONFIGURED,
                               details={"line": line, "model_version": requested_model})
    return {"model_version": match["model_version"], "algorithm": algo,
            "family": family, "model": model}