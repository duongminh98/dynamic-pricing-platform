"""Claim outcome read-model table for calibration drift.

Revision ID: g6d7e8f9a0b1
Revises: f5a6b7c8d9e0
Create Date: 2026-06-28 02:30:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'g6d7e8f9a0b1'
down_revision: Union[str, Sequence[str], None] = 'f5a6b7c8d9e0'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'claim_outcome',
        sa.Column('claim_id', sa.String(), nullable=False),
        sa.Column('quote_id', sa.String(), nullable=True),
        sa.Column('policy_id', sa.String(), nullable=True),
        sa.Column('line', sa.String(), nullable=True),
        sa.Column('actual_loss_vnd', sa.BigInteger(), nullable=True),
        sa.Column('settled_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('recorded_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('claim_id'),
    )
    op.create_index('idx_claim_outcome_quote_id', 'claim_outcome', ['quote_id'])
    op.create_index('idx_claim_outcome_line', 'claim_outcome', ['line'])


def downgrade() -> None:
    op.drop_index('idx_claim_outcome_line', table_name='claim_outcome')
    op.drop_index('idx_claim_outcome_quote_id', table_name='claim_outcome')
    op.drop_table('claim_outcome')
