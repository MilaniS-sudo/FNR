import importlib
import io
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

os.environ["FNR_STT"] = "mock"
os.environ["FNR_SEGMENTER"] = "heuristic"
os.environ["FNR_APP_TOKEN"] = "test-token"
os.environ["FNR_RATE_LIMIT_PER_HOUR"] = "3"

import config  # noqa: E402

importlib.reload(config)
import segmenter  # noqa: E402
import main  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

client = TestClient(main.app)
HEADERS = {"X-FNR-Token": "test-token"}


@pytest.fixture(autouse=True)
def reset_rate_limit():
    main._requests.clear()
    yield


def _post(headers=None, name="lecture.m4a", content=b"fake audio bytes", duration=90_000):
    return client.post(
        "/v1/process",
        headers=headers if headers is not None else HEADERS,
        files={"audio": (name, io.BytesIO(content), "audio/mp4")},
        data={"language": "en", "duration_ms": str(duration)},
    )


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["stt"] == "mock"


def test_rejects_missing_or_wrong_token():
    assert _post(headers={}).status_code == 401
    assert _post(headers={"X-FNR-Token": "wrong"}).status_code == 401


def test_rejects_wrong_file_type():
    r = _post(name="notes.pdf")
    assert r.status_code == 400
    assert "Unsupported" in r.json()["detail"]


def test_rejects_empty_file():
    assert _post(content=b"").status_code == 400


def test_process_returns_valid_key_points():
    r = _post(duration=90_000)
    assert r.status_code == 200
    body = r.json()
    assert body["segments"]
    points = body["key_points"]
    assert len(points) >= 3
    last_end = 0
    for p in points:
        assert 0 <= p["start_ms"] < p["end_ms"] <= 90_000
        assert 5_000 <= p["end_ms"] - p["start_ms"] <= 120_000
        assert p["start_ms"] >= last_end  # no overlaps
        assert 3 <= len(p["title"]) <= 100
        last_end = p["end_ms"]
    concepts = {p["concept"] for p in points}
    assert any("normalisation" in c for c in concepts)


def test_rate_limit():
    for _ in range(3):
        assert _post().status_code == 200
    assert _post().status_code == 429


def test_clean_points_fixes_bad_ai_output():
    segments = [{"start_ms": 0, "end_ms": 60_000, "text": "x"}]
    raw = [
        {"title": "A", "start_ms": 50_000, "end_ms": 10_000},  # reversed -> dropped
        {"title": "Short", "start_ms": 1_000, "end_ms": 2_000},  # too short -> extended
        {"title": "Overlap", "start_ms": 3_000, "end_ms": 30_000},  # overlaps -> shifted
        {"title": "Past end", "start_ms": 40_000, "end_ms": 999_999},  # clamped
        {"title": "bad", "start_ms": "x", "end_ms": None},  # dropped
    ]
    out = segmenter.clean_points(raw, segments)
    assert [p["title"] for p in out] == ["Short", "Overlap", "Past end"]
    assert out[0]["end_ms"] - out[0]["start_ms"] == 5_000
    assert out[1]["start_ms"] == out[0]["end_ms"]
    assert out[2]["end_ms"] == 60_000


def test_heuristic_without_cues_uses_windows():
    segments = [
        {"start_ms": i * 10_000, "end_ms": (i + 1) * 10_000, "text": "routers forward packets across networks"}
        for i in range(9)
    ]
    points, model = segmenter.find_key_points(segments)
    assert model == "heuristic-v1"
    assert len(points) >= 2
    assert points[0]["concept"]
