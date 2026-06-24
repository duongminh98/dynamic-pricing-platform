"""Model drift flag table (task 23.2, R37.7).

Revision ID: c3d4e5f6a7b8
Revises: b2c3d4e5f6a7
Create Date: 2026-06-25 02:00:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'c3d4e5f6a7b8'
down_revision: Union[str, Sequence[str], None] = 'b2c3d4e5f6a7'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

def upgrade() -> None:
    op.create_table(
        'model_drift_flag',
        sa.Column('flag_id', sa.String(), nullable=False),
        sa.Column('line', sa.String(), nullable=False),
        sa.Column('metric', sa.String(), nullable=False),
        sa.Column('value', sa.Float(), nullable=False),
        sa.Column('threshold', sa.Float(), nullable=False),
        sa.Column('needs_recalibration', sa.Boolean(), nullable=False, server_default='false'),
        sa.Column('computed_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('flag_id'),
    )
    op.create_index('idx_drift_line', 'model_drift_flag', ['line'])

def downgrade() -> None:
    op.drop_index('idx_drift_line', table_name='model_drift_flag')
    op.drop_table('model_drift_flag')
