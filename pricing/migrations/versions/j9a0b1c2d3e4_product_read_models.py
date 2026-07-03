"""Add product catalog and loading factor read models.

Revision ID: j9a0b1c2d3e4
Revises: i8f9a0b1c2d3
Create Date: 2026-07-01 00:00:00.000000
"""
from alembic import op
import sqlalchemy as sa

revision = 'j9a0b1c2d3e4'
down_revision = 'i8f9a0b1c2d3'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        'product_catalog_item',
        sa.Column('product_id', sa.String(), nullable=False),
        sa.Column('category', sa.String(), nullable=False),
        sa.Column('product_name', sa.String(), nullable=True),
        sa.Column('coverage_amount_vnd', sa.BigInteger(), nullable=False, server_default='0'),
        sa.Column('deductible_vnd', sa.BigInteger(), nullable=False, server_default='0'),
        sa.Column('base_premium_vnd', sa.BigInteger(), nullable=False, server_default='0'),
        sa.Column('admin_fee_vnd', sa.BigInteger(), nullable=False, server_default='0'),
        sa.Column('active', sa.Boolean(), nullable=False, server_default=sa.text('true')),
        sa.Column('last_event_id', sa.String(), nullable=True),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('product_id'),
    )
    op.create_index('idx_product_catalog_item_category_active', 'product_catalog_item', ['category', 'active'])
    op.create_index('idx_product_catalog_item_event', 'product_catalog_item', ['last_event_id'])

    op.create_table(
        'product_loading_factor',
        sa.Column('line', sa.String(), nullable=False),
        sa.Column('rate_version_id', sa.String(), nullable=True),
        sa.Column('loading_value', sa.Float(), nullable=False, server_default='1.0'),
        sa.Column('last_event_id', sa.String(), nullable=True),
        sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('line'),
    )
    op.create_index('idx_product_loading_factor_rate_version', 'product_loading_factor', ['rate_version_id'])
    op.create_index('idx_product_loading_factor_event', 'product_loading_factor', ['last_event_id'])


def downgrade() -> None:
    op.drop_index('idx_product_loading_factor_event', table_name='product_loading_factor')
    op.drop_index('idx_product_loading_factor_rate_version', table_name='product_loading_factor')
    op.drop_table('product_loading_factor')
    op.drop_index('idx_product_catalog_item_event', table_name='product_catalog_item')
    op.drop_index('idx_product_catalog_item_category_active', table_name='product_catalog_item')
    op.drop_table('product_catalog_item')
