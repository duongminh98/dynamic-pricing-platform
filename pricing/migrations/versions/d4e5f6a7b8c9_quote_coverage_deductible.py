"""Persist coverage_amount_vnd + deductible_vnd on quote (claims payout cap).

These are supplied in the quote request profile but were not persisted, so the
order-service could not propagate them to the issued policy's exposure segment
(payout cap = coverage - deductible). Add them as nullable integer columns
(default 0) so GET /pricing/quote/{id} can return them for downstream issuance.

Revision ID: d4e5f6a7b8c9
Revises: c3d4e5f6a7b8
Create Date: 2026-06-25 03:30:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'd4e5f6a7b8c9'
down_revision: Union[str, Sequence[str], None] = 'c3d4e5f6a7b8'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('quote', sa.Column('coverage_amount_vnd', sa.BigInteger(), nullable=True, server_default='0'))
    op.add_column('quote', sa.Column('deductible_vnd', sa.BigInteger(), nullable=True, server_default='0'))


def downgrade() -> None:
    op.drop_column('quote', 'deductible_vnd')
    op.drop_column('quote', 'coverage_amount_vnd')
