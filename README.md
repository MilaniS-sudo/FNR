# Flash Note Recorder (FNR)

Richfield BSc IT, Year 3 IT Project. Records a student explaining a topic out loud,
finds the key points with AI, and turns them into short replayable clips of the
student's own voice.

```
fnr/
├── fnr-android/   Android app (Kotlin, XML, Room + SQLCipher, MVVM) – open in Android Studio
└── fnr-gateway/   AI Gateway (Python, FastAPI, local Whisper) – run on your Mac
```

## Six modules

| Module | Where in the code |
| --- | --- |
| M1 User and Security | `security/`, `data/repository/UserRepository.kt`, `ui/auth/`, `ui/profile/` |
| M2 Recording and Upload | `audio/`, `RecordingRepository`, `ui/record/` |
| M3 AI Processing | `work/Workers.kt` (ProcessRecordingWorker), `data/remote/`, `fnr-gateway/` |
| M4 Library and Playback | `FlashNoteRepository`, `SubjectRepository`, `ui/library/` |
| M5 Reports | `ReportRepository`, `util/ReportPdfExporter.kt`, `ui/reports/` |
| M6 Backup and Admin | `BackupRepository`, `AutoBackupWorker`, `ui/backup/`, `ui/admin/` |

## Quick start

1. Start the Gateway: `cd fnr-gateway && ./run_gateway.sh`
2. Open `fnr-android` in Android Studio, let Gradle sync, press Run.
3. Register (the first account is the administrator), register a second account as a student, record, generate flash notes.

The full step-by-step guide, including testing and the demo, is in the
"FNR Build and Run Guide" document.
