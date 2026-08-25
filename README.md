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

The project endpoint accepts a complete video-project JSON document and preserves its existing metadata. It sorts `scenes` by `order`, synthesizes each non-blank `narration` as a separate MP3, and applies `tts.languageCode`, `tts.voiceName`, `tts.speakingRate`, and `tts.pitch` when present.

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

`tts.languageCode` falls back to `project.language`, then `en-US`, and `project.fps` falls back to 30. Long scenes are automatically split below Google's per-request SSML limit.

The response and generated `*-timed.json` contain:

- one MP3 per scene for frame-accurate video placement;
- a combined preview MP3 and one continuous SRT;
- `startSec`, `endSec`, `actualDurationSec`, `startFrame`, `endFrameExclusive`, and `durationInFrames` for every scene;
- `differenceFromEstimateSec`, `differenceFromEstimatePercent`, and `reviewStatus` without overwriting the original `estimatedDurationSec`;
- `GOOGLE_SSML_MARK` when Google returned the real end time, or `ESTIMATED_FALLBACK` when manual review is required;
- `audioTiming` for total duration and frames, `videoStrategy.actualLengthSec`, and calculated YouTube chapter timestamps.

A scene is marked `REVIEW` when its actual duration differs from the estimate by more than 0.5 seconds or 10%, whichever is larger. Scenes without narration, estimates, or Google timing marks receive a specific review status.

Use each scene's `timing.durationInFrames` and `audio.file` in the video renderer. The combined MP3 is intended for preview or export; scene MP3 files are the synchronization source of truth.

## Test and package

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```
