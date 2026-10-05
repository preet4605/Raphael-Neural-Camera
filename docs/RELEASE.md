# Release & Distribution Guide

## 1. Build Verification Workflow
To compile and assemble the debug application package:

```bash
# 1. Run all multi-module unit tests
./gradlew test

# 2. Compile and assemble debug APK
./gradlew :app:assembleDebug
```

## 2. Release Distribution Criteria
1. **Zero Test Regressions**: All 13 module unit test suites must pass 100%.
2. **Quality Gate Pass**: Evaluated against the 14 controlled reference scenes in Data Lab.
3. **Artifact Integrity**: APK file size and MD5 checksum recorded and reported.
4. **Target Hardware**: OnePlus 15 (SM8850 Snapdragon 8 Elite Gen 5, 3rd-generation Qualcomm Oryon, 12GB RAM, Android 16 Baklava). Not yet verified: no proof gate has passed (see [`PROOF_GATES.md`](PROOF_GATES.md)).
