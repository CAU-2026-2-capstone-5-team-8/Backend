package com.cau.capstone8.backend.assessment;

import java.util.List;

/** Explicit list of approved handoff files to import together; see generated-question-handoff-v2.md. */
record QuestionImportManifest(String manifestVersion, List<Entry> entries) {
    static final String VERSION = "question-import-manifest-v1";

    record Entry(String generatedPath, String reviewsPath, String groundingPath) {}
}
