# Text to Speech with synchronized SRT

A Spring Boot application that generates an MP3 file and a synchronized SRT subtitle file from English text using Google Cloud Text-to-Speech.

Subtitle timing is not based solely on text-length estimates. The service splits the input into readable cues, inserts SSML `<mark>` elements, and uses the timepoints returned by Google Cloud TTS. Character-based interpolation is used only if the API omits a timepoint.

## Requirements

- JDK 17 or later
- A Google Cloud project with the Text-to-Speech API enabled
- Google Application Default Credentials (ADC) or a service-account JSON file

## Google Cloud authentication

For normal local development, set the standard Google environment variable:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS='C:\path\to\service-account.json'
```

The application reads this variable through `google.cloud.credentials.path` in `application.yml`. If the variable is empty, the Google SDK falls back to ADC, including credentials created with:

```powershell
gcloud auth application-default login
```

`application.yml` also contains a safe `local-test` profile example. Replace its placeholder path locally or set the environment variable, then run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local-test
```

Never commit a real service-account JSON file or secret.

## Voice model

The default language is `en-US`, and the default voice is `en-US-Neural2-F`. Neural2 provides natural neural speech while supporting the SSML `<mark>` timepoints required for synchronized subtitles.

Override the default voice with an environment variable:

```powershell
$env:TTS_DEFAULT_ENGLISH_VOICE='en-US-Neural2-J'
```

You can also pass `voiceName` in each JSON request. Chirp 3 HD is newer, but it does not currently support `<mark>` timepoints, so it is not recommended when accurate SRT synchronization is required.

The Korean project endpoint uses `ko-KR-Standard-C` by default. Google lists this as a male Korean Standard voice; Standard voices have the larger monthly free tier. Override it with `TTS_DEFAULT_KOREAN_VOICE` if needed.

## Run

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
.\mvnw.cmd spring-boot:run
```

## API

Plain-text request using the backward-compatible endpoint:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/text/convert-to-audio' `
  -ContentType 'text/plain; charset=utf-8' `
  -Body 'Welcome to our service. Your audio and subtitles are ready.'
```

JSON request with voice controls:

```powershell
$body = @{
  text = 'This is the first sentence. This is the second sentence.'
  languageCode = 'en-US'
  voiceName = 'en-US-Neural2-F'
  speakingRate = 1.0
  pitch = 0.0
} | ConvertTo-Json

Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/text/synthesize' `
  -ContentType 'application/json' `
  -Body $body
```

The response contains unique MP3 and SRT paths and download URLs. Files are stored in `output/` by default. Override the directory with `TTS_OUTPUT_DIRECTORY`.

### Video project JSON

The project endpoint accepts a complete video-project JSON document and preserves its existing metadata. It sorts `scenes` by `order`, synthesizes each non-blank `narration` as a separate MP3, and applies `tts.languageCode`, `tts.voiceName`, `tts.speakingRate`, `tts.pitch`, `tts.sentencePauseMsMin`, and `tts.sentencePauseMsMax` when present. A scene-level `sentencePauseMs` array takes precedence and applies one value to each sentence ending in that scene, in order. If the array is shorter than the number of sentences, the remaining sentences use the root `tts` midpoint pause.

```powershell
$result = Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/text/synthesize-project' `
  -ContentType 'application/json' `
  -InFile 'C:\path\to\video_project.json'

Invoke-WebRequest -Uri "http://localhost:8080$($result.audioUrl)" -OutFile '.\project.mp3'
Invoke-WebRequest -Uri "http://localhost:8080$($result.subtitleUrl)" -OutFile '.\project.srt'
Invoke-WebRequest -Uri "http://localhost:8080$($result.timedProjectUrl)" -OutFile '.\project-timed.json'

$result.scenes | Format-Table `
  sceneId, actualDurationSec, durationInFrames, differenceFromEstimateSec, timingSource, reviewStatus
```

For Korean narration with the default free-tier male voice, use the separate endpoint:

```powershell
$result = Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/text/synthesize-project2' `
  -ContentType 'application/json' `
  -InFile 'C:\path\to\video_project.json'
```

This endpoint forces `languageCode=ko-KR` and `voiceName=ko-KR-Standard-C`, even if the input JSON contains another language or voice. The speaking rate, pitch, and sentence pause settings from the JSON are still used.

### Chirp 3 HD project with multiple speakers

`POST /text/synthesize-project3` starts a background job and returns HTTP 202 with a `jobId` and `statusUrl` immediately. Poll `GET /text/synthesize-project3/jobs/{jobId}` for `QUEUED`, `RUNNING`, `COMPLETED`, or `FAILED`. The status includes completed/total segments, percentage, current scene/segment, update time, and (when complete) the original output result with WAV/SRT/timed-JSON URLs. Console output and `logs/tts.log` contain the job ID and each segment's start/completion, plus a stack trace on failure (`TTS_LOG_FILE` overrides the log path). One project runs at a time; up to eight more can wait in the queue. Status is kept in memory for 24 hours after completion, so restarting the app clears it. `POST /text/synthesize-project3/sync` preserves the previous wait-for-result behavior.

The endpoint reads `tts.voiceMap` and each scene's ordered `ttsSegments`. A segment's `speakerId` selects its voice. The `narration` field stays in the output as metadata; the segment text is what gets spoken. All speaker IDs must be mapped before any Google request is sent.

```json
{
  "project": {"language": "ko-KR", "fps": 30},
  "tts": {
    "provider": "GOOGLE_CHIRP3_HD",
    "speakingRate": 0.88,
    "pitch": -3,
    "voiceMap": {
      "daughter": "ko-KR-Chirp3-HD-Kore",
      "father": "ko-KR-Chirp3-HD-Orus",
      "mother": "ko-KR-Chirp3-HD-Gacrux"
    }
  },
  "scenes": [{
    "id": "scene-01",
    "order": 1,
    "ttsSegments": [
      {"speakerId": "daughter", "type": "NARRATION", "text": "아버지가 말했습니다."},
      {"speakerId": "father", "type": "DIALOGUE", "text": "한 달만 늦추자."}
    ]
  }]
}
```

```powershell
$job = Invoke-RestMethod `
  -Method Post `
  -Uri 'http://localhost:8080/text/synthesize-project3' `
  -ContentType 'application/json' `
  -InFile 'C:\path\to\video_project.json'

$job.statusUrl
do {
  Start-Sleep -Seconds 2
  $status = Invoke-RestMethod -Uri "http://localhost:8080$($job.statusUrl)"
  "$($status.status): $($status.completedSegments)/$($status.totalSegments) ($($status.progressPercent)%)"
} while ($status.status -in @('QUEUED', 'RUNNING'))

if ($status.status -ne 'COMPLETED') { throw $status.error }
$result = $status.result
Invoke-WebRequest -Uri "http://localhost:8080$($result.audioUrl)" -OutFile '.\project.wav'
Invoke-WebRequest -Uri "http://localhost:8080$($result.subtitleUrl)" -OutFile '.\project.srt'
Invoke-WebRequest -Uri "http://localhost:8080$($result.timedProjectUrl)" -OutFile '.\project-timed.json'
```

Project3 outputs 24 kHz mono PCM WAV files, one per scene and one combined, plus SRT and `*-timed.json`. WAV sample counts provide exact segment and scene durations without requiring FFmpeg. The timed JSON adds `ttsSegments[].voiceName`, `ttsSegments[].timing`, scene timing, and measured start times for object-form YouTube chapters. Chirp 3 HD does not return `<mark>` timepoints: SRT cue starts within each segment are proportional estimates, while segment and scene boundaries are measured from audio. `audioTiming.subtitleTimingSource` records this limitation.

`speakingRate` uses Chirp pace control. `pitch` is sent through Chirp's preview SSML `<prosody>` support. Scene `sentencePauseMs` values take precedence over the root min/max midpoint and are inserted as SSML `<break>` tags at sentence punctuation. Because this Chirp feature is in preview, test the final voice and pauses by listening to the WAV.

`tts.languageCode` falls back to `project.language`, then `en-US`, and `project.fps` falls back to 30. Long scenes are automatically split below Google's per-request SSML limit.

The response and generated `*-timed.json` contain:

- one MP3 per scene for frame-accurate video placement;
- a combined preview MP3 and one continuous SRT;
- `startSec`, `endSec`, `actualDurationSec`, `startFrame`, `endFrameExclusive`, and `durationInFrames` for every scene;
- `differenceFromEstimateSec`, `differenceFromEstimatePercent`, and `reviewStatus` without overwriting the original `estimatedDurationSec`;
- `GOOGLE_SSML_MARK` when Google returned the real end time, or `ESTIMATED_FALLBACK` when manual review is required;
- `audioTiming` for total duration and frames, `videoStrategy.actualLengthSec`, and calculated YouTube chapter timestamps.
- `audioTiming.sentencePauseMsApplied` records the midpoint pause used for reproducible synthesis; the original min/max settings are preserved in the timed `tts` object.
- Each scene's `timing.sentencePauseMsApplied` and `timing.sentencePauseMode` show the scene-level pause values used (`scene` or `tts-default`).

A scene is marked `REVIEW` when its actual duration differs from the estimate by more than 0.5 seconds or 10%, whichever is larger. Scenes without narration, estimates, or Google timing marks receive a specific review status.

Use each scene's `timing.durationInFrames` and `audio.file` in the video renderer. The combined MP3 is intended for preview or export; scene MP3 files are the synchronization source of truth.

## Test and package

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```
