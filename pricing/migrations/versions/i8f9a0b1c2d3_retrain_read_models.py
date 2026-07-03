"""Add retrain-ready pricing read models.

Revision ID: i8f9a0b1c2d3
Revises: h7e8f9a0b1c2
Create Date: 2026-06-30 20:30:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = 'i8f9a0b1c2d3'
down_revision: Union[str, Sequence[str], None] = 'h7e8f9a0b1c2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

def upgrade() -> None:
    op.add_column('claim_outcome', sa.Column('exposure_segment_seq', sa.Integer(), nullable=True))
    op.add_column('claim_outcome', sa.Column('loss_type', sa.String(), nullable=True))
    op.add_column('claim_outcome', sa.Column('incurred_amount_vnd', sa.BigInteger(), nullable=True))
    op.add_column('claim_outcome', sa.Column('paid_amount_vnd', sa.BigInteger(), nullable=True))
    op.add_column('claim_outcome', sa.Column('claim_status', sa.String(), nullable=True))
    op.add_column('claim_outcome', sa.Column('occurrence_date', sa.DateTime(timezone=True), nullable=True))
    op.add_column('claim_outcome', sa.Column('reported_at', sa.DateTime(timezone=True), nullable=True))
    op.create_index('idx_claim_outcome_policy_occurrence', 'claim_outcome', ['policy_id', 'occurrence_date'])

    op.create_table(
        'quote_feature_snapshot',
        sa.Column('quote_id', sa.String(), nullable=False),
        sa.Column('customer_id', sa.String(), nullable=False),
        sa.Column('product_id', sa.String(), nullable=False),
        sa.Column('line', sa.String(), nullable=False),
        sa.Column('input_profile', sa.JSON(), nullable=False),
        sa.Column('enriched_profile', sa.JSON(), nullable=False),
        sa.Column('feature_set', sa.JSON(), nullable=False),
        sa.Column('model_version_id', sa.String(), nullable=True),
        sa.Column('rate_version_id', sa.String(), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('quote_id'),
    )
    op.create_index('idx_quote_feature_snapshot_customer_line', 'quote_feature_snapshot', ['customer_id', 'line'])

    op.create_table(
        'policy_exposure',
        sa.Column('exposure_id', sa.String(), nullable=False),
        sa.Column('policy_id', sa.String(), nullable=False),
        sa.Column('quote_id', sa.String(), nullable=True),
        sa.Column('customer_id', sa.String(), nullable=False),
        sa.Column('product_id', sa.String(), nullable=True),
        sa.Column('line', sa.String(), nullable=False),
        sa.Column('exposure_segment_seq', sa.Integer(), nullable=False),
        sa.Column('segment_start', sa.DateTime(timezone=True), nullable=False),
        sa.Column('segment_end', sa.DateTime(timezone=True), nullable=False),
        sa.Column('earned_exposure_years', sa.Float(), nullable=False),
        sa.Column('coverage_amount_vnd', sa.BigInteger(), nullable=True),
        sa.Column('deductible_vnd', sa.BigInteger(), nullable=True),
        sa.Column('final_premium_vnd', sa.BigInteger(), nullable=True),
        sa.Column('status', sa.String(), nullable=True),
        sa.Column('risk_snapshot', sa.JSON(), nullable=True),
        sa.Column('source_event_type', sa.String(), nullable=True),
        sa.Column('recorded_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('exposure_id'),
    )
    op.create_index('idx_policy_exposure_policy_seq', 'policy_exposure', ['policy_id', 'exposure_segment_seq'])
    op.create_index('idx_policy_exposure_customer_line', 'policy_exposure', ['customer_id', 'line'])
    op.create_index('idx_policy_exposure_quote', 'policy_exposure', ['quote_id'])

    op.create_table(
        'customer_risk_profile',
        sa.Column('customer_id', sa.String(), nullable=False),
        sa.Column('profile_version', sa.Integer(), nullable=False),
        sa.Column('effective_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('common_risk_attributes', sa.JSON(), nullable=False),
        sa.Column('line_risk_attributes', sa.JSON(), nullable=False),
        sa.Column('last_event_id', sa.String(), nullable=True),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('customer_id'),
    )
    op.create_index('idx_customer_risk_profile_event', 'customer_risk_profile', ['last_event_id'])

def downgrade() -> None:
    op.drop_index('idx_customer_risk_profile_event', table_name='customer_risk_profile')
    op.drop_table('customer_risk_profile')

    op.drop_index('idx_policy_exposure_quote', table_name='policy_exposure')
    op.drop_index('idx_policy_exposure_customer_line', table_name='policy_exposure')
    op.drop_index('idx_policy_exposure_policy_seq', table_name='policy_exposure')
    op.drop_table('policy_exposure')

    op.drop_index('idx_quote_feature_snapshot_customer_line', table_name='quote_feature_snapshot')
    op.drop_table('quote_feature_snapshot')

    op.drop_index('idx_claim_outcome_policy_occurrence', table_name='claim_outcome')
    op.drop_column('claim_outcome', 'reported_at')
    op.drop_column('claim_outcome', 'occurrence_date')
    op.drop_column('claim_outcome', 'claim_status')
    op.drop_column('claim_outcome', 'paid_amount_vnd')
    op.drop_column('claim_outcome', 'incurred_amount_vnd')
    op.drop_column('claim_outcome', 'loss_type')
    op.drop_column('claim_outcome', 'exposure_segment_seq')
