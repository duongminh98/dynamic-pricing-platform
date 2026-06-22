"""
Pytest configuration — adds the pricing directory to sys.path
so that 'common.*' imports resolve correctly.
"""

import sys
import os

# Add pricing/ to sys.path so 'common' package is importable
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
