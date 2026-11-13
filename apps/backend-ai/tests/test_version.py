"""Versión y entorno del servicio."""

import pytest

from app.version import DEVELOPMENT, __version__, normalize_environment


def test_version_is_semver():
    parts = __version__.split(".")
    assert len(parts) == 3 and all(part.isdigit() for part in parts)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (None, DEVELOPMENT),
        ("", DEVELOPMENT),
        ("   ", DEVELOPMENT),
        ("dev", DEVELOPMENT),
        (" LOCAL ", DEVELOPMENT),
        ("development", DEVELOPMENT),
        ("prod", "production"),
        ("Production", "production"),
        ("stage", "staging"),
        ("staging", "staging"),
        ("QA", "qa"),
    ],
)
def test_normalize_environment(raw, expected):
    assert normalize_environment(raw) == expected
