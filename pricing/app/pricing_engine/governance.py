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

from ..database import ModelVersion, ChampionAssignment, AuditTrail, EventOutbox
from ..config import is_monotonic_exempt
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
    """Promote a candidate only if offline gates and metrics pass."""
    challenger = _get_model(db, challenger_version_id)
    if challenger.line != line:
        raise ServiceException(ErrorCode.BAD_REQUEST,
                               details={"line": line, "reason": "line mismatch"})
    if (challenger.status or "CANDIDATE") != "CANDIDATE":
        _audit(db, line, "CHAMPION_PROMOTE_REJECTED",
               {"reason": "not_candidate", "challenger": challenger_version_id, "status": challenger.status},
               actor)
        return {"promoted": False, "reason": "NOT_CANDIDATE_STATUS",
                "champion": _current_champion(db, line).model_version_id if _current_champion(db, line) else None}

    gates = challenger.quality_gates or {}
    if not gates.get("comparison_passed", False):
        _audit(db, line, "CHAMPION_PROMOTE_REJECTED",
               {"reason": "comparison_failed", "challenger": challenger_version_id},
               actor)
        return {"promoted": False, "reason": "COMPARISON_NOT_PASSED",
                "champion": _current_champion(db, line).model_version_id if _current_champion(db, line) else None}
    if not gates.get("smoothness_passed", True):
        _audit(db, line, "CHAMPION_PROMOTE_REJECTED",
               {"reason": "smoothness_failed", "challenger": challenger_version_id},
               actor)
        return {"promoted": False, "reason": "SMOOTHNESS_GATE_FAILED",
                "champion": _current_champion(db, line).model_version_id if _current_champion(db, line) else None}

    current = _current_champion(db, line)
    challenger_metric = getattr(challenger, PRIMARY_METRIC, 0.0) or 0.0
    current_metric = getattr(current, PRIMARY_METRIC, 0.0) or 0.0 if current else -1.0

    # BR-23: promote iff metric improves AND the monotonic constraint is satisfied.
    #
    # BR-19 travel exemption (task 20.8b): a GLM champion on a monotonic-exempt
    # line (see config.MONOTONIC_EXEMPT_LINES) does not carry artifact-level
    # monotone_constraints; its coefficient signs are enforced at fit time, so it
    # may promote on the Gini criterion alone. The exemption is GLM-only: any
    # tree / LightGBM candidate STILL requires monotonic_applied=true, regardless
    # of line, so BR-19 stays enforced for non-exempt lines.
    exempt = is_monotonic_exempt(line, challenger.algorithm)
    if not exempt and not challenger.monotonic_applied:
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
                              is_current=True,
                              created_at=datetime.datetime.now(datetime.timezone.utc)))
    challenger.status = "CHAMPION"
    if current is not None:
        current.status = "ARCHIVED"
    detail = {"line": line,
              "action": "promote",
              "old": current.model_version_id if current else None,
              "new": challenger_version_id,
              "challenger_gini": challenger_metric,
              "champion_gini": current_metric,
              "comparison_report_uri": challenger.comparison_report_uri,
              "quality_gates": gates}
    _audit(db, line, "CHAMPION_CHANGE", detail, actor)
    _publish_event(db, "ChampionPromoted", detail)
    db.commit()
    return {"promoted": True, "champion": challenger_version_id}

def reject_candidate(db: Session, line: str, model_version_id: str, actor: str = "admin") -> dict:
    candidate = _get_model(db, model_version_id)
    if candidate.line != line:
        raise ServiceException(ErrorCode.BAD_REQUEST,
                               details={"line": line, "reason": "line mismatch"})
    if (candidate.status or "CANDIDATE") != "CANDIDATE":
        return {"rejected": False, "reason": "NOT_CANDIDATE_STATUS", "status": candidate.status}
    candidate.status = "REJECTED"
    detail = {
        "line": line,
        "action": "reject",
        "model_version_id": model_version_id,
        "comparison_report_uri": candidate.comparison_report_uri,
    }
    _audit(db, line, "MODEL_CANDIDATE_REJECTED", detail, actor)
    db.commit()
    return {"rejected": True, "model_version_id": model_version_id}


def rollback_champion(db: Session, line: str, actor: str = "admin") -> dict:
    """Roll back to the prior champion by appending a new current assignment (R37.5/R37.8)."""
    history = db.query(ChampionAssignment).filter(
        ChampionAssignment.line == line,
    ).order_by(ChampionAssignment.created_at.desc().nullslast()).all()
    current = next((a for a in history if a.is_current), None)
    # The most recent assignment that is not the current model is the rollback target.
    previous = None
    for a in history:
        if current is not None and a.model_version_id == current.model_version_id:
            continue
        previous = a
        break
    if previous is None:
        raise ServiceException(ErrorCode.BAD_REQUEST,
                               details={"line": line, "reason": "no previous champion"})
    # Append-only: flip current off, INSERT a new current row pointing to the prior model.
    db.query(ChampionAssignment).filter(
        ChampionAssignment.line == line,
        ChampionAssignment.is_current.is_(True),
    ).update({"is_current": False})
    db.add(ChampionAssignment(assignment_id=str(uuid.uuid4()),
                              line=line,
                              model_version_id=previous.model_version_id,
                              is_current=True,
                              created_at=datetime.datetime.now(datetime.timezone.utc)))
    prev_model = _get_model(db, previous.model_version_id)
    prev_model.status = "CHAMPION"
    if current is not None:
        curr_model = _get_model(db, current.model_version_id)
        curr_model.status = "ARCHIVED"
    detail = {"line": line,
              "action": "rollback",
              "old": current.model_version_id if current else None,
              "restored": previous.model_version_id}
    _audit(db, line, "CHAMPION_CHANGE", detail, actor)
    _publish_event(db, "ChampionRolledBack", detail)
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


def _publish_event(db: Session, event_type: str, detail: dict) -> None:
    """Append an outbox row so champion changes are published to platform.events
    (routing key = event_type) for downstream consumers (R37.9). A separate relay
    delivers NEW rows; writing it in the same transaction preserves atomicity."""
    db.add(EventOutbox(
        event_id=str(uuid.uuid4()),
        event_type=event_type,
        routing_key=event_type,
        payload=detail,
        status="NEW",
        created_at=datetime.datetime.now(datetime.timezone.utc),
    ))
