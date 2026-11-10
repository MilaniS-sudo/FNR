"""Key-point detection: turns a timestamped transcript into flash notes.

Each backend returns (key_points, model_name) where key_points is a list of
    {"title": str, "summary": str, "concept": str, "start_ms": int, "end_ms": int}

All output passes through `clean_points`, which enforces the rules from the
Phase 4 design: times must come from the transcript, clips are 5-120 seconds,
no overlaps, and text fields have length limits. That stops the AI from
inventing timestamps or returning clips the app cannot play.
"""
from __future__ import annotations

import json
import logging
import re
from collections import Counter

import httpx

import config

log = logging.getLogger("fnr.segmenter")

MAX_POINTS = 15
TARGET_CLIP_MS = 40_000


def find_key_points(segments: list[dict]) -> tuple[list[dict], str]:
    if not segments:
        return [], "none"
    backend = config.SEGMENTER_BACKEND
    if backend in ("openai", "gemini"):
        try:
            points = _llm(segments, backend)
            cleaned = clean_points(points, segments)
            if cleaned:
                model = config.OPENAI_TEXT_MODEL if backend == "openai" else config.GEMINI_MODEL
                return cleaned, model
            log.warning("LLM returned no usable key points; using heuristic fallback")
        except Exception as exc:  # network, quota, bad JSON ...
            log.warning("LLM segmenter failed (%s); using heuristic fallback", exc)
        return clean_points(_heuristic(segments), segments), "heuristic-fallback"
    return clean_points(_heuristic(segments), segments), "heuristic-v1"


# ------------------------------------------------------------ validation ---
def clean_points(points: list[dict], segments: list[dict]) -> list[dict]:
    total_ms = max(s["end_ms"] for s in segments)
    result: list[dict] = []
    def sort_key(x: dict) -> int:
        value = _to_int(x.get("start_ms"))
        return value if value is not None else -1

    for p in sorted(points, key=sort_key):
        start = _to_int(p.get("start_ms"))
        end = _to_int(p.get("end_ms"))
        if start is None or end is None:
            continue
        start = max(0, min(start, total_ms))
        end = max(0, min(end, total_ms))
        if end <= start:
            continue
        # Extend short clips, trim long ones
        if end - start < config.MIN_CLIP_MS:
            end = min(total_ms, start + config.MIN_CLIP_MS)
            if end - start < config.MIN_CLIP_MS:
                start = max(0, end - config.MIN_CLIP_MS)
        if end - start > config.MAX_CLIP_MS:
            end = start + config.MAX_CLIP_MS
        # No overlap with the previous clip
        if result and start < result[-1]["end_ms"]:
            start = result[-1]["end_ms"]
            if end - start < config.MIN_CLIP_MS:
                continue
        title = _clip_text(str(p.get("title") or ""), 100)
        summary = _clip_text(str(p.get("summary") or ""), 500)
        concept = _clip_text(str(p.get("concept") or ""), 60)
        if len(title) < 3:
            title = (concept or "Key point").title()
            if len(title) < 3:
                title = "Key point"
        result.append(
            {"title": title, "summary": summary, "concept": concept, "start_ms": start, "end_ms": end}
        )
        if len(result) >= MAX_POINTS:
            break
    return result


def _to_int(value) -> int | None:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def _clip_text(text: str, limit: int) -> str:
    text = " ".join(text.split())
    if len(text) <= limit:
        return text
    cut = text[: limit - 1]
    if " " in cut:
        cut = cut.rsplit(" ", 1)[0]
    return cut + "…"


# ------------------------------------------------------------- heuristic ---
STOPWORDS = set(
    """a about above after again against all also am an and any are as at be because been before
    being below between both but by can could did do does doing down during each few for from
    further had has have having he her here hers herself him himself his how i if in into is it its
    itself just let like me more most my myself no nor not now of off on once only or other our ours
    ourselves out over own really right same she should so some such than that the their theirs them
    themselves then there these they this those through to too under until up very was we were what
    when where which while who whom why will with would you your yours yourself yourselves okay ok
    um uh yeah thing things something going gonna want know think say said one two first second
    third next today basically actually means mean called refers defined important example comes
    come goes talk talking look looking""".split()
)

DEFINITION_CUES = [
    r"\bis defined as\b", r"\bare defined as\b", r"\brefers? to\b", r"\bmeans\b",
    r"\bis called\b", r"\bare called\b", r"\bis known as\b", r"\bdefinition\b",
    r"\bis an?\b", r"\bis the\b", r"\bare the\b",
]
EMPHASIS_CUES = [
    r"\bimportant\b", r"\bkey\b", r"\bremember\b", r"\bin summary\b", r"\bto summari[sz]e\b",
    r"\bthe main\b", r"\bfor example\b", r"\bfirst(ly)?\b", r"\bsecond(ly)?\b", r"\bfinally\b",
    r"\bthe (first|second|third|next|last) (phase|step|stage|part)\b", r"\bbecause\b",
    r"\badvantage\b", r"\bdisadvantage\b", r"\bdifference between\b",
]
# Strong cue anywhere in the sentence: "... analysis, which refers to ..."
STRONG_CONCEPT = re.compile(
    r"([a-z][a-z0-9\-]*(?: [a-z][a-z0-9\-]*){0,4}),?(?: which| that)?\s+"
    r"(?:is defined as|are defined as|refers to|refer to|means|is called|are called|is known as)\b"
)
# Weak cue at the start of the sentence: "A primary key is a ..."
WEAK_CONCEPT = re.compile(
    r"^(?:so |and |now |okay |ok |well |right )*(?:a |an |the )?"
    r"([a-z][a-z0-9\- ]{1,40}?)\s+(?:is an?|is the|are the)\b"
)


def _score(text: str) -> tuple[int, bool]:
    lower = text.lower()
    definition = any(re.search(c, lower) for c in DEFINITION_CUES[:8])
    score = 0
    if definition:
        score += 3
    elif any(re.search(c, lower) for c in DEFINITION_CUES[8:]):
        score += 1
    score += sum(1 for c in EMPHASIS_CUES if re.search(c, lower))
    if len(lower.split()) < 5:
        score -= 1
    return score, definition


def _concept_of(text: str) -> str:
    lower = text.lower().strip()
    for pattern in (STRONG_CONCEPT, WEAK_CONCEPT):
        match = pattern.search(lower)
        if not match:
            continue
        words = match.group(1).split()
        # Drop filler words at the front ("next comes analysis" -> "analysis")
        while words and words[0] in STOPWORDS | {"comes", "come", "then", "this", "it"}:
            words.pop(0)
        if words and len(words) <= 5 and words[-1] not in STOPWORDS:
            return " ".join(words)
    return _top_keyword(text)


def _top_keyword(text: str) -> str:
    words = [w for w in re.findall(r"[a-z][a-z\-]{3,}", text.lower()) if w not in STOPWORDS]
    if not words:
        return ""
    bigrams = Counter(f"{a} {b}" for a, b in zip(words, words[1:]))
    top_bigram, count = bigrams.most_common(1)[0] if bigrams else ("", 0)
    if count >= 2:
        return top_bigram
    return Counter(words).most_common(1)[0][0]


def _first_sentences(text: str, limit: int = 280) -> str:
    sentences = re.split(r"(?<=[.!?])\s+", text.strip())
    out = ""
    for s in sentences:
        if len(out) + len(s) + 1 > limit and out:
            break
        out = f"{out} {s}".strip()
    return out


def _heuristic(segments: list[dict]) -> list[dict]:
    scored = [(i, *_score(s["text"])) for i, s in enumerate(segments)]
    candidates = [i for i, score, _ in scored if score >= 2]

    if not candidates:  # no cues found: fall back to evenly sized windows
        return _windows(segments)

    points: list[dict] = []
    used_until = -1
    for idx in candidates:
        if idx <= used_until:
            continue
        start_ms = segments[idx]["start_ms"]
        end_idx = idx
        # Grow the clip with following segments until it is long enough,
        # stopping before the next definition so each note has one idea.
        while end_idx + 1 < len(segments):
            nxt = end_idx + 1
            length = segments[nxt]["end_ms"] - start_ms
            if length > TARGET_CLIP_MS:
                break
            if scored[nxt][2] and segments[end_idx]["end_ms"] - start_ms >= config.MIN_CLIP_MS:
                break
            end_idx = nxt
        used_until = end_idx
        text = " ".join(s["text"] for s in segments[idx : end_idx + 1])
        concept = _concept_of(segments[idx]["text"])
        points.append(
            {
                "title": concept.title() if concept else "Key point",
                "summary": _first_sentences(text),
                "concept": concept,
                "start_ms": start_ms,
                "end_ms": segments[end_idx]["end_ms"],
            }
        )
    return points


def _windows(segments: list[dict]) -> list[dict]:
    points, group = [], []
    for seg in segments:
        group.append(seg)
        if group[-1]["end_ms"] - group[0]["start_ms"] >= TARGET_CLIP_MS:
            points.append(_window_point(group))
            group = []
    if group:
        points.append(_window_point(group))
    return points


def _window_point(group: list[dict]) -> dict:
    text = " ".join(s["text"] for s in group)
    concept = _top_keyword(text)
    return {
        "title": concept.title() if concept else "Study segment",
        "summary": _first_sentences(text),
        "concept": concept,
        "start_ms": group[0]["start_ms"],
        "end_ms": group[-1]["end_ms"],
    }


# ------------------------------------------------------------------- LLM ---
PROMPT = """You help a student revise from a recording of themselves studying out loud.
Below are timestamped transcript segments in the form [start_ms-end_ms] text.

Pick out the key learning points: definitions, key concepts and explanations.
Return ONLY JSON in this exact shape:
{"key_points": [{"title": "...", "summary": "...", "concept": "...", "start_ms": 0, "end_ms": 0}]}

Rules:
- start_ms and end_ms MUST be copied from the segment boundaries below.
- Each clip should be 5 to 120 seconds long and must not overlap another clip.
- title: 3 to 100 characters. concept: the main term, at most 60 characters.
- summary: at most 2 sentences, using ONLY what the student actually said. Do not add facts.
- At most 15 key points, in time order. Skip small talk.

Segments:
"""


def _llm(segments: list[dict], backend: str) -> list[dict]:
    lines = "\n".join(f"[{s['start_ms']}-{s['end_ms']}] {s['text']}" for s in segments)
    prompt = PROMPT + lines
    raw = _call_openai(prompt) if backend == "openai" else _call_gemini(prompt)
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", raw, re.S)
        if not match:
            raise
        data = json.loads(match.group(0))
    points = data.get("key_points", []) if isinstance(data, dict) else []
    if not isinstance(points, list):
        raise ValueError("key_points is not a list")
    return [p for p in points if isinstance(p, dict)]


def _call_openai(prompt: str) -> str:
    if not config.OPENAI_API_KEY:
        raise RuntimeError("OPENAI_API_KEY is not set")
    resp = httpx.post(
        "https://api.openai.com/v1/chat/completions",
        headers={"Authorization": f"Bearer {config.OPENAI_API_KEY}"},
        json={
            "model": config.OPENAI_TEXT_MODEL,
            "response_format": {"type": "json_object"},
            "temperature": 0.2,
            "messages": [{"role": "user", "content": prompt}],
        },
        timeout=120,
    )
    resp.raise_for_status()
    return resp.json()["choices"][0]["message"]["content"]


def _call_gemini(prompt: str) -> str:
    if not config.GEMINI_API_KEY:
        raise RuntimeError("GEMINI_API_KEY is not set")
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{config.GEMINI_MODEL}:generateContent"
    resp = httpx.post(
        url,
        headers={"x-goog-api-key": config.GEMINI_API_KEY},
        json={
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {"temperature": 0.2, "responseMimeType": "application/json"},
        },
        timeout=120,
    )
    resp.raise_for_status()
    return resp.json()["candidates"][0]["content"]["parts"][0]["text"]
