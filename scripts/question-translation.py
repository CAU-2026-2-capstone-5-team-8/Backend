#!/usr/bin/env python3
"""Private Gemini adapter. No answer keys, explanations or account data in payload."""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "Question-Generation/src"))
from dotenv import load_dotenv
from question_generation.config import load_settings
from question_generation.translation import translate_question


def main():
    payload = json.load(sys.stdin)
    load_dotenv(ROOT / "Question-Generation/.env", override=False)
    os.environ["QUESTION_GENERATION_LANGUAGE"] = "en-US"
    settings = load_settings(require_api_key=True)
    source = {k: payload[k] for k in ["passage", "prompt", "choices"]}
    output = (
        Path(sys.argv[1]).resolve()
        / "question-translations"
        / str(int(payload["questionId"]))
    )
    result = translate_question(source, output, settings)
    node = os.getenv("TOPIC_CONTENT_RENDER_NODE") or shutil.which("node")
    if not node:
        raise ValueError("translation_renderer_missing")
    rendered = subprocess.run(
        [
            node,
            "--experimental-strip-types",
            str(ROOT / "Frontend/native/scripts/validate-display-translation.mjs"),
        ],
        input=json.dumps(result["text"]),
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
        cwd=ROOT / "Frontend/native",
    )
    if rendered.returncode != 0:
        raise ValueError("translation_not_renderable")
    proof = json.loads(rendered.stdout)
    if proof != {
        "fields": 5 + int(result["text"]["passage"] is not None),
        "invalidMath": 0,
    }:
        raise ValueError("translation_render_count_differs")
    return {
        "status": result["status"],
        "sourceHash": payload["sourceHash"],
        "language": result["language"],
        "version": result["version"],
        "model": settings.model,
        **result["text"],
    }


if __name__ == "__main__":
    try:
        print(json.dumps(main(), ensure_ascii=False))
    except Exception as exc:  # noqa: BLE001 - sanitized worker boundary
        failure = {"error": "question_translation_failed", "kind": type(exc).__name__}
        code = getattr(exc, "code", None)
        if isinstance(code, int) and 100 <= code <= 599:
            failure["providerStatus"] = code
        print(json.dumps(failure))
        sys.exit(1)
