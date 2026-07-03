"""Add quote-ready customer-line projection.

Revision ID: k0b1c2d3e4f5
Revises: j9a0b1c2d3e4
Create Date: 2026-07-01 00:00:00.000000
"""
from alembic import op
import sqlalchemy as sa

revision = 'k0b1c2d3e4f5'
down_revision = 'j9a0b1c2d3e4'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        'quote_ready_profile',
        sa.Column('customer_id', sa.String(), nullable=False),
        sa.Column('line', sa.String(), nullable=False),
        sa.Column('profile_version', sa.Integer(), nullable=False),
        sa.Column('enriched_profile', sa.JSON(), nullable=False),
        sa.Column('claim_features', sa.JSON(), nullable=False),
        sa.Column('last_profile_event_id', sa.String(), nullable=True),
        sa.Column('last_claim_event_id', sa.String(), nullable=True),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('customer_id', 'line'),
    )
    op.create_index('idx_quote_ready_profile_updated', 'quote_ready_profile', ['updated_at'])


def downgrade() -> None:
    op.drop_index('idx_quote_ready_profile_updated', table_name='quote_ready_profile')
    op.drop_table('quote_ready_profile')
