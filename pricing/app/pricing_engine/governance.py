"""Champion promotion / rollback governance (Property 24, BR-23).

A challenger is promoted to champion IF AND ONLY IF it beats the current
champion on the configured primary metric (Gini) AND its artifact satisfies
the monotonic constraint. Otherwise the current champion is retained. Every
promotion / rollback is recorded as an append-only Audit_Trail entry and
emits a ChampionPromoted / ChampionRolledBack event.

Requirements: R37.4, R37.5, R37.9, R37.10 (design 6.3, 6.4).
"""
from __future__ import annotations

import datetime
import uuid

from sqlalchemy.orm import Session

from ..database import ModelVersion, ChampionAssignment, AuditTrail
from . import loader
from common.errors import ErrorCode, ServiceException

PRIMARY_METRIC = "gini"


def _get_model(db: Session, model_version_id: str) -> ModelVersion:
    model = db.query(ModelVersion).filter(
        ModelVersion.model_version_id == model_version_id).first()
    if model is None:
        raise ServiceException(ErrorCode.BAD_REQUEST,
                               details={"model_version_id": model_version_id,
                                        "reason": "not found"})
    return model


def _current_champion(db: Session, line: str) -> ModelVersion | None:
    assignment = db.query(ChampionAssignment).filter(
        ChampionAssignment.line == line,
        ChampionAssignment.is_current.is_(True),
    ).first()
    if assignment is None:
        return None
    return db.query(ModelVersion).filter(
        ModelVersion.model_version_id == assignment.model_version_id).first()


def promote_champion(db: Session, line: str, challenger_version_id: str,
                     actor: str = "admin") -> dict:
    """Promote a challenger only if Gini beats champion AND monotonic applied."""
    challenger = _get_model(db, challenger_version_id)
    if challenger.line != line:
        raise ServiceException(ErrorCode.BAD_REQUEST,
                               details={"line": line, "reason": "line mismatch"})

    current = _current_champion(db, line)
    challenger_metric = getattr(challenger, PRIMARY_METRIC, 0.0) or 0.0
    current_metric = getattr(current, PRIMARY_METRIC, 0.0) or 0.0 if current else -1.0

    # BR-23: promote iff metric improves AND monotonic constraint satisfied.
    if not challenger.monotonic_applied:
        _audit(db, line, "CHAMPION_PROMOTE_REJECTED",
               {"reason": "monotonic_not_applied", "challenger": challenger_version_id},
               actor)
        return {"promoted": False, "reason": "MONOTONIC_NOT_APPLIED",
                "champion": current.model_version_id if current else None}
    if challenger_metric <= current_metric:
        _audit(db, line, "CHAMPION_PROMOTE_REJECTED",
               {"reason": "gini_not_improved",
                "challenger_gini": challenger_metric,
                "champion_gini": current_metric},
               actor)
        return {"promoted": False, "reason": "GINI_NOT_IMPROVED",
                "champion": current.model_version_id if current else None}

    # Flip previous assignment off (append-only: INSERT new, set old is_current=False).
    db.query(ChampionAssignment).filter(
        ChampionAssignment.line == line,
        ChampionAssignment.is_current.is_(True),
    ).update({"is_current": False})
    db.add(ChampionAssignment(assignment_id=str(uuid.uuid4()),
                              line=line,
                              model_version_id=challenger_version_id,
                              is_current=True))
    _audit(db, line, "ChampionPromoted",
           {"old": current.model_version_id if current else None,
            "new": challenger_version_id,
            "challenger_gini": challenger_metric,
            "champion_gini": current_metric}, actor)
    db.commit()
    return {"promoted": True, "champion": challenger_version_id}


def rollback_champion(db: Session, line: str, actor: str = "admin") -> dict:
    """Roll back to the most recent non-current champion for the line."""
    previous = db.query(ChampionAssignment).filter(
        ChampionAssignment.line == line,
        ChampionAssignment.is_current.is_(False),
    ).order_by(ChampionAssignment.line.desc()).first()
    if previous is None:
        raise ServiceException(ErrorCode.BAD_REQUEST,
                               details={"line": line, "reason": "no previous champion"})
    db.query(ChampionAssignment).filter(
        ChampionAssignment.line == line,
        ChampionAssignment.is_current.is_(True),
    ).update({"is_current": False})
    db.query(ChampionAssignment).filter(
        ChampionAssignment.line == line,
        ChampionAssignment.model_version_id == previous.model_version_id,
    ).update({"is_current": True})
    _audit(db, line, "ChampionRolledBack",
           {"restored": previous.model_version_id}, actor)
    db.commit()
    return {"rolled_back": True, "champion": previous.model_version_id}


def _audit(db: Session, line: str, event_type: str, detail: dict, actor: str) -> None:
    db.add(AuditTrail(
        audit_id=str(uuid.uuid4()),
        event_type=event_type,
        change_detail=dict(detail, line=line),
        actor=actor,
        created_at=datetime.datetime.now(datetime.timezone.utc),
    ))