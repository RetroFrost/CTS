"""CTS desktop entry point.

The rewrite branch intentionally launches the clean application package directly. Legacy
window and renderer modules remain in the repository only for project migration and visual
comparison while the rewrite is validated.
"""

from .rewrite.main import main

__all__ = ["main"]
