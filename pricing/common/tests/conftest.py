"""
Pytest configuration for the common-module tests.

Adds the pricing/ directory (this file's parent.parent.parent) to sys.path so
that the ``common`` package resolves as a top-level import (``from common.* ...``).

NOTE: we deliberately insert the ``pricing/`` directory here, NOT
``pricing/common``. Inserting ``pricing/common`` would expose this directory's
own ``tests`` sub-package as a top-level ``tests`` module, which collides with
``pricing/tests`` when both suites are collected in the same pytest session
(e.g. ``pytest pricing/tests pricing/common/tests``). Anchoring on ``pricing/``
keeps ``common`` importable while leaving ``pricing/tests`` as the single
top-level ``tests`` package.
"""

import os
import sys

PRICING_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
if PRICING_DIR not in sys.path:
    sys.path.insert(0, PRICING_DIR)
