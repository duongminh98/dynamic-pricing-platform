"""Add pricing reference read models and quote reference version ids.

Revision ID: n3e4f5g6h7i8
Revises: m2d3e4f5g6h7
Create Date: 2026-07-01 12:00:00.000000
"""
from alembic import op
import sqlalchemy as sa

revision = 'n3e4f5g6h7i8'
down_revision = 'm2d3e4f5g6h7'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('quote', sa.Column('geo_risk_version_id', sa.String(), nullable=True))
    op.add_column('quote', sa.Column('cost_index_version_id', sa.String(), nullable=True))

    op.create_table(
        'geo_risk_reference_row',
        sa.Column('version_id', sa.String(), nullable=False),
        sa.Column('province', sa.String(), nullable=False),
        sa.Column('region', sa.String(), nullable=True),
        sa.Column('urban_tier_geo', sa.String(), nullable=True),
        sa.Column('traffic_density_score', sa.Float(), nullable=False, server_default='0'),
        sa.Column('vehicle_theft_risk_score', sa.Float(), nullable=False, server_default='0'),
        sa.Column('accident_frequency_index', sa.Float(), nullable=False, server_default='0'),
        sa.Column('flood_risk_score', sa.Float(), nullable=False, server_default='0'),
        sa.Column('storm_risk_score', sa.Float(), nullable=False, server_default='0'),
        sa.Column('fire_risk_score', sa.Float(), nullable=False, server_default='0'),
        sa.Column('crime_risk_score', sa.Float(), nullable=False, server_default='0'),
        sa.Column('healthcare_access_score', sa.Float(), nullable=False, server_default='0'),
        sa.Column('hospital_cost_index', sa.Float(), nullable=False, server_default='0'),
        sa.Column('repair_cost_index', sa.Float(), nullable=False, server_default='0'),
        sa.Column('construction_cost_index', sa.Float(), nullable=False, server_default='0'),
        sa.Column('status', sa.String(), nullable=False, server_default='ACTIVE'),
        sa.Column('checksum', sa.String(), nullable=True),
        sa.Column('last_event_id', sa.String(), nullable=True),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('version_id', 'province'),
    )
    op.create_index('idx_geo_risk_reference_status', 'geo_risk_reference_row', ['status'])

    op.create_table(
        'cost_index_reference_row',
        sa.Column('version_id', sa.String(), nullable=False),
        sa.Column('month_start', sa.String(), nullable=False),
        sa.Column('year', sa.Integer(), nullable=False),
        sa.Column('month', sa.Integer(), nullable=False),
        sa.Column('medical_inflation_index', sa.Float(), nullable=False, server_default='1.0'),
        sa.Column('vehicle_repair_inflation_index', sa.Float(), nullable=False, server_default='1.0'),
        sa.Column('construction_inflation_index', sa.Float(), nullable=False, server_default='1.0'),
        sa.Column('travel_medical_cost_index', sa.Float(), nullable=False, server_default='1.0'),
        sa.Column('general_expense_index', sa.Float(), nullable=False, server_default='1.0'),
        sa.Column('status', sa.String(), nullable=False, server_default='ACTIVE'),
        sa.Column('checksum', sa.String(), nullable=True),
        sa.Column('last_event_id', sa.String(), nullable=True),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('version_id', 'month_start'),
    )
    op.create_index('idx_cost_index_reference_status', 'cost_index_reference_row', ['status'])


def downgrade() -> None:
    op.drop_index('idx_cost_index_reference_status', table_name='cost_index_reference_row')
    op.drop_table('cost_index_reference_row')
    op.drop_index('idx_geo_risk_reference_status', table_name='geo_risk_reference_row')
    op.drop_table('geo_risk_reference_row')
    op.drop_column('quote', 'cost_index_version_id')
    op.drop_column('quote', 'geo_risk_version_id')


