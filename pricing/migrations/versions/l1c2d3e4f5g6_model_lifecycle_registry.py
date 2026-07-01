"""Add no-A/B model lifecycle registry fields.

Revision ID: l1c2d3e4f5g6
Revises: k0b1c2d3e4f5
Create Date: 2026-07-01 00:00:00.000000
"""
from alembic import op
import sqlalchemy as sa

revision = 'l1c2d3e4f5g6'
down_revision = 'k0b1c2d3e4f5'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        'training_dataset_version',
        sa.Column('dataset_version_id', sa.String(), nullable=False),
        sa.Column('source_type', sa.String(), nullable=False),
        sa.Column('artifact_uri', sa.String(), nullable=False),
        sa.Column('manifest_uri', sa.String(), nullable=False),
        sa.Column('data_hash', sa.String(), nullable=False),
        sa.Column('window_start', sa.DateTime(timezone=True), nullable=True),
        sa.Column('window_end', sa.DateTime(timezone=True), nullable=True),
        sa.Column('export_started_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('export_completed_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('status', sa.String(), nullable=False, server_default='EXPORTED'),
        sa.Column('frequency_rows', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('severity_rows', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('exposure_rows', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('settled_claim_rows', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('quote_snapshot_rows', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('created_by', sa.String(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.PrimaryKeyConstraint('dataset_version_id'),
    )
    op.create_table(
        'training_dataset_file',
        sa.Column('file_id', sa.String(), nullable=False),
        sa.Column('dataset_version_id', sa.String(), nullable=False),
        sa.Column('line', sa.String(), nullable=True),
        sa.Column('kind', sa.String(), nullable=False),
        sa.Column('artifact_uri', sa.String(), nullable=False),
        sa.Column('row_count', sa.Integer(), nullable=False, server_default='0'),
        sa.Column('checksum_sha256', sa.String(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(['dataset_version_id'], ['training_dataset_version.dataset_version_id']),
        sa.PrimaryKeyConstraint('file_id'),
    )
    with op.batch_alter_table('model_version') as batch:
        batch.add_column(sa.Column('family', sa.String(), nullable=True))
        batch.add_column(sa.Column('status', sa.String(), nullable=False, server_default='CANDIDATE'))
        batch.add_column(sa.Column('dataset_version_id', sa.String(), nullable=True))
        batch.add_column(sa.Column('artifact_uri', sa.String(), nullable=True))
        batch.add_column(sa.Column('artifact_checksum', sa.String(), nullable=True))
        batch.add_column(sa.Column('feature_schema_hash', sa.String(), nullable=True))
        batch.add_column(sa.Column('comparison_report_uri', sa.String(), nullable=True))
        batch.add_column(sa.Column('validation_report_uri', sa.String(), nullable=True))
        batch.add_column(sa.Column('fairness_report_uri', sa.String(), nullable=True))
        batch.add_column(sa.Column('registered_at', sa.DateTime(timezone=True), nullable=True))
        batch.add_column(sa.Column('registered_by', sa.String(), nullable=True))
        batch.add_column(sa.Column('training_code_version', sa.String(), nullable=True))
        batch.add_column(sa.Column('quality_gates', sa.JSON(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table('model_version') as batch:
        for name in ['quality_gates','training_code_version','registered_by','registered_at','fairness_report_uri','validation_report_uri','comparison_report_uri','feature_schema_hash','artifact_checksum','artifact_uri','dataset_version_id','status','family']:
            batch.drop_column(name)
    op.drop_table('training_dataset_file')
    op.drop_table('training_dataset_version')
