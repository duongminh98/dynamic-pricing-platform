"""Backfill lifecycle status for current champions.

Revision ID: m2d3e4f5g6h7
Revises: l1c2d3e4f5g6
Create Date: 2026-07-01 00:00:00.000000
"""
from alembic import op

revision = 'm2d3e4f5g6h7'
down_revision = 'l1c2d3e4f5g6'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        UPDATE model_version mv
        SET status = 'CHAMPION'
        FROM champion_assignment ca
        WHERE ca.model_version_id = mv.model_version_id
          AND ca.is_current = TRUE
        """
    )
    op.execute(
        """
        UPDATE model_version mv
        SET status = 'ARCHIVED'
        WHERE EXISTS (
            SELECT 1
            FROM champion_assignment ca
            WHERE ca.model_version_id = mv.model_version_id
              AND ca.is_current = FALSE
        )
          AND NOT EXISTS (
            SELECT 1
            FROM champion_assignment ca
            WHERE ca.model_version_id = mv.model_version_id
              AND ca.is_current = TRUE
        )
          AND mv.status = 'CANDIDATE'
        """
    )


def downgrade() -> None:
    pass
