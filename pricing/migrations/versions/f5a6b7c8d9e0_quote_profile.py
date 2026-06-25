"""Persist the raw rating profile on quote (endorsement re-rate base).

The quote request profile holds the full set of risk attributes used to price a
product. It was not persisted, so the order-service could not propagate it to the
issued policy. Without the full profile an endorsement re-rate could only send the
changed attributes, which the pricing engine rejects (MISSING_FEATURES) or
mis-prices via defaults. Persisting it lets issuance stamp the policy's first
exposure segment with the full profile so endorsements can merge changes onto a
complete base and re-rate correctly.

Revision ID: f5a6b7c8d9e0
Revises: d4e5f6a7b8c9
Create Date: 2026-06-25 06:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = 'f5a6b7c8d9e0'
down_revision: Union[str, Sequence[str], None] = 'd4e5f6a7b8c9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('quote', sa.Column('profile', postgresql.JSON(astext_type=sa.Text()), nullable=True))


def downgrade() -> None:
    op.drop_column('quote', 'profile')
