#!/usr/bin/env python3
"""Verify frozen generation artifacts before a bounded, cached AI review."""
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'Question-Generation/src'))
from question_generation.automatic_review import review_batch
from question_generation.concept_batch import save_json
from question_generation.concept_contract import content_hash
from question_generation.concept_generation import file_hash
from question_generation.config import load_settings
from question_generation.revision_batch import revise_batch

def validate_rendering(generation_directory, count):
    renderer = ROOT / 'Frontend/native/scripts/validate-question-content.mjs'
    renderer_module = ROOT / 'Frontend/native/src/lib/questionContent.ts'
    node = os.getenv('TOPIC_CONTENT_RENDER_NODE') or shutil.which('node')
    if not node:
        raise ValueError('question_renderer_unavailable')
    rendered = subprocess.run(
        [node, '--experimental-strip-types', str(renderer), str(generation_directory)],
        cwd=ROOT / 'Frontend/native', capture_output=True, text=True, timeout=30,
    )
    if rendered.returncode != 0:
        raise ValueError('question_content_not_renderable')
    validation = json.loads(rendered.stdout)
    if validation != {'questions': count, 'fields': count * 6, 'invalidMath': 0}:
        raise ValueError('question_render_count_differs')
    return {'status': 'PASSED', **validation,
        'rendererHash': file_hash(renderer_module), 'validatorHash': file_hash(renderer)}

def main():
    payload = json.load(sys.stdin)
    directory = Path(sys.argv[1]).resolve() / f"request-{int(payload['requestId'])}"
    source = Path(payload['contentReportPath']).resolve(strict=True)
    generation = Path(payload['generationReportPath']).resolve(strict=True)
    for path, key in ((source, 'contentReportHash'), (generation, 'generationReportHash')):
        if not path.is_relative_to(directory) or file_hash(path) != payload[key]:
            raise ValueError('review_source_changed')
    content = json.loads(source.read_text()); generated = json.loads(generation.read_text())
    if (generated['status'] != 'CANDIDATES_READY' or generated['topicId'] != payload['slug']
        or generated['sourceSnapshotId'] != payload['sourceSnapshotId']
        or generated['contentReportHash'] != payload['contentReportHash']
        or content['sourceSnapshotId'] != payload['sourceSnapshotId']):
        raise ValueError('review_source_differs')
    for filename, digest in content['artifactHashes'].items():
        if filename not in {'outline.json','book-profiles.jsonl','targets.json','blueprint.json','configs/features.yaml','configs/concept_graph.yaml','configs/concept_matching_v2.yaml'}:
            raise ValueError('unknown_content_artifact')
        path = (source.parent / filename).resolve(strict=True)
        if not path.is_relative_to(source.parent) or file_hash(path) != digest:
            raise ValueError('content_artifact_changed')
    for filename, digest in generated['candidateHashes'].items():
        path = (generation.parent / filename).resolve(strict=True)
        if not path.is_relative_to(generation.parent) or file_hash(path) != digest:
            raise ValueError('candidate_changed')
    # Use the actual storefront renderer; a syntax/schema pass alone is not
    # proof that KaTeX can display every frozen question field.
    count = len(generated['candidateHashes'])
    render_proof = validate_rendering(generation.parent, count)
    os.environ['QUESTION_GENERATION_LANGUAGE'] = 'en-US'
    generation_settings = load_settings(require_api_key=True)
    os.environ['QUESTION_GENERATION_MODEL'] = os.getenv('TOPIC_REVIEW_MODEL', os.getenv('QUESTION_GENERATION_MODEL','gemini-3.5-flash-lite'))
    settings = load_settings(require_api_key=True)
    identity = {'generation': payload['generationReportHash'], 'content': payload['contentReportHash'],
        'reviewModel': settings.model, 'implementation': file_hash(ROOT/'Question-Generation/src/question_generation/automatic_review.py'), 'adapter': file_hash(Path(__file__))}
    output = generation.parent / 'ai-review' / content_hash(identity)[7:31]
    report = review_batch(source.parent/'blueprint.json', generation.parent, source.parent/'outline.json', output, settings)
    report.update(sourceSnapshotId=payload['sourceSnapshotId'], contentReportHash=payload['contentReportHash'], generationReportHash=payload['generationReportHash'], renderValidation=render_proof)
    report['revisionPossible'] = (generated.get('revisionRound', 0) == 0 and report['status'] == 'REVIEW_BLOCKED'
        and report['graphApproved'] and 1 <= report['plannedCount'] - report['approvedCount'] <= 2)
    save_json(output/'review-report.json', report)
    if payload.get('allowRevision') and report['revisionPossible']:
        # A separate worker tick owns this step, keeping provider calls bounded.
        revised = revise_batch(source.parent/'blueprint.json', generation.parent, generation,
            output/'review-report.json', generation_settings)
        report['revisedGenerationPath'] = str(revised)
        report['revisedGenerationHash'] = file_hash(revised)
        save_json(output/'revision-handoff.json', report)
        output_report = output/'revision-handoff.json'
    else:
        output_report = output/'review-report.json'
    return {'status':report['status'],'slug':payload['slug'],'sourceSnapshotId':payload['sourceSnapshotId'],
        'reportPath':str(output_report),'reportHash':file_hash(output_report)}
if __name__ == '__main__':
    try:
        print(json.dumps(main()))
    except Exception as exc:
        print(json.dumps({'error':'question_review_failed','kind':type(exc).__name__}));sys.exit(1)
