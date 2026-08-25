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

## Test and package

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```
