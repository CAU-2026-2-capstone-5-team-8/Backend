#!/usr/bin/env python3
"""Start the local backend on its saved DB and activate the selected manifest.

Private config: {"java": "/path/to/java", "env": {"POSTGRES_HOST": "...", ...}}.
Keep it in ignored .local/runtime.json; never commit credentials.
"""

import argparse
import json
import os
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--config", type=Path, default=root / ".local/runtime.json")
p.add_argument(
    "--manifest", type=Path, default=root / ".local/catalog/current-selection.json"
)
args = p.parse_args()
manifest = args.manifest.resolve(strict=True)
config = json.loads(args.config.read_text())
env = dict(os.environ, **config["env"])
# Local topic preparation is opt-in; API keys stay in the component .env files.
preparation_args = []
if env.get("TOPIC_PREPARATION_ENABLED", "false").lower() == "true":
    preparation_args = [
        "--topic-preparation.enabled=true",
        "--topic-preparation.python="
        + str(root.parent / "Data-Pipeline/.venv/bin/python"),
        "--topic-preparation.script=" + str(root / "scripts/topic-preparation.py"),
        "--topic-preparation.workspace=" + str(root / ".local/topic-preparation"),
        "--topic-content-preparation.enabled="
        + str(env.get("TOPIC_CONTENT_PREPARATION_ENABLED", "false")).lower(),
        "--topic-question-preparation.enabled="
        + str(env.get("TOPIC_QUESTION_PREPARATION_ENABLED", "false")).lower(),
    ]
java = config["java"]
jar = root / "build/libs/backend-0.0.1-SNAPSHOT.jar"
if not jar.is_file():
    raise SystemExit("Build the backend bootJar first.")
os.chdir(root)
os.execve(
    java,
    [
        java,
        "-jar",
        str(jar),
        "--spring.profiles.active=local",
        "--catalog-selection.manifest-path=" + str(manifest),
        *preparation_args,
    ],
    env,
)
