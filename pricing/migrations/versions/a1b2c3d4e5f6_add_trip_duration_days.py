"""Add trip_duration_days to quote (R22.3 travel term)

Revision ID: a1b2c3d4e5f6
Revises: e653fdc7d96a
Create Date: 2026-06-24 00:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'a1b2c3d4e5f6'
down_revision: Union[str, Sequence[str], None] = 'e653fdc7d96a'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('quote', sa.Column('trip_duration_days', sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column('quote', 'trip_duration_days')
