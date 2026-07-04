"""Add missing quote.explanation column.

The Quote ORM model (app/database.py) declares ``explanation = Column(JSON)``
but no migration ever added the column — every other quote column has one, so
this was a schema-drift gap that only surfaces on a DB built purely from
migrations (the quote INSERT in routers/quote.py writes ``explanation``).

Revision ID: o4f5g6h7i8j9
Revises: n3e4f5g6h7i8
Create Date: 2026-07-04 00:00:00.000000
"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = 'o4f5g6h7i8j9'
down_revision = 'n3e4f5g6h7i8'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column('quote', sa.Column('explanation', postgresql.JSON(astext_type=sa.Text()), nullable=True))


def downgrade() -> None:
    op.drop_column('quote', 'explanation')
