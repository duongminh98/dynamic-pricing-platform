"""Add customer_id to claim outcome read model.

Revision ID: h7e8f9a0b1c2
Revises: g6d7e8f9a0b1
Create Date: 2026-06-30 19:10:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'h7e8f9a0b1c2'
down_revision: Union[str, Sequence[str], None] = 'g6d7e8f9a0b1'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

def upgrade() -> None:
    op.add_column('claim_outcome', sa.Column('customer_id', sa.String(), nullable=True))
    op.create_index('idx_claim_outcome_customer_line', 'claim_outcome', ['customer_id', 'line'])

def downgrade() -> None:
    op.drop_index('idx_claim_outcome_customer_line', table_name='claim_outcome')
    op.drop_column('claim_outcome', 'customer_id')
