# Questions for Michael (cloud/track-list-matching)

No blocking questions so far. Open, non-blocking questions are collected here and repeated in the PR
description under "Questions for Michael".

## Test gate (per standing decision B)

`./gradlew test` cannot run in this cloud sandbox (dl.google.com and musicbrainz.org are blocked), so the gate
is a plain-Java run: `core/*.java` + `ui/FetchJob.java` and every JVM unit test, compiled with JDK 21
(`--release 17`) against junit 4.13.2, hamcrest-core 1.3 and org.json 20231013 (the versions in
app/build.gradle, fetched from Maven Central with SHA-1 checked), run with `org.junit.runner.JUnitCore`.

- Before any change (main @ 1b8f69b): **OK (82 tests)**. Two of those are `LiveSmokeTest`, which skips itself
  unless `AMCA_LIVE=1`.
