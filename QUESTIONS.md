# Questions for Michael (cloud/track-list-matching)

None of these blocked the work. Each is a choice I left as-is rather than decide for you; they are repeated in
the PR description under "Questions for Michael".

1. **Privacy policy.** The track-list fallback sends up to five track titles to MusicBrainz. PRIVACY.md listed
   exactly what is sent (ID, or artist + album title + track count), so it would have become wrong. I added one
   sentence to PRIVACY.md and adjusted the README's "Nothing about you or your music is sent". I did **not**
   change the policy's effective date (28 October 2026). Please check the wording, set the date, and update any
   copy of the policy hosted elsewhere.
2. **Albums split into one folder per disc.** The fallback needs the release's *total* track count to equal the
   files in the folder, so a "Disc 1" folder of a 2-CD compilation never matches by track list. Matching on one
   medium's count (MusicBrainz `tracksmedium`) would catch those, but is looser. Keep strict?
3. **No album title at all.** When neither tags nor the folder name give an album title, the app still stops at
   "no tags" before any search. The fallback could run on artist + track titles alone. Not done.
4. **Rip Error Check** finds its release with the same title search. Should it use the track-list fallback too?
   Not done.
5. **Showing how it matched.** A cover found by track list looks the same in the UI as one found by title
   ("Found · saved folder.jpg"); only the run log's source URL differs. Want a hint such as "found by track list"?
6. **Two albums that both fit** (same songs sold as, say, a UK and a US compilation in different release groups)
   are skipped as "no confident match". "Various Artists" compilations will almost never match, because the
   search filters on the recording's artist. Both are deliberate; say if you want otherwise.
7. **Time cost.** Only albums with no confident title match pay for the fallback: one recording search plus at
   most two release lookups, i.e. up to about 4.5 s extra at the app's 1.5 s MusicBrainz spacing. Albums that
   already match make exactly the same requests as before. A library with many unmatched albums will take longer.
8. **Titles that normalise to nothing** (all non-Latin script, e.g. Japanese or Cyrillic, because `Text.norm`
   keeps only a–z and 0–9) are never used as samples, so those albums never use the fallback.

## Test gate (per standing decision B)

`./gradlew test` cannot run in this cloud sandbox (dl.google.com and musicbrainz.org are blocked), so the gate
is a plain-Java run: `core/*.java` + `ui/FetchJob.java` and every JVM unit test, compiled with JDK 21
(`--release 17`) against junit 4.13.2, hamcrest-core 1.3 and org.json 20231013 (the versions in
app/build.gradle, fetched from Maven Central with SHA-1 checked), with `src/test/resources` on the classpath,
run with `org.junit.runner.JUnitCore`.

- Before any change (main @ 1b8f69b): **OK (82 tests)**. Two of those are `LiveSmokeTest`, which skips itself
  unless `AMCA_LIVE=1`.
- After: **OK (107 tests)**: the same 82 plus 23 in the new `TrackListMatchTest` and 2 in `TagReaderTest`.

## Michael's answers (round 2, 2026-11-05)

1. Privacy: **open, Michael's.** He checks the wording, sets the effective date, updates the hosted copy before release.
2. One folder per disc: **keep strict.**
3. No album title: **not now.**
4. Rip Error Check fallback: **later, as a separate change.**
5. Show how it matched: **yes.** Done: "Found by track list · saved folder.jpg" while running, "Added (by track list)" in the report (`LookupResult.byTrackList`). README says so.
6. Two albums fit / Various Artists: **keep.**
7. Time cost: **fine.**
8. Non-Latin titles: **fine for now**, listed in the README as a known limit.

Test gate after round 2 (plain Java, JDK 21 `--release 17`, Hetzner): **OK (107 tests)**, with new assertions in the
two confident-fallback tests and the title-match test. `MainActivity` is outside this gate; the Gradle build on
desktop compiles it.
