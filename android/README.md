# JSTB for Android

Helps you send many job applications with little typing, in two builds:

| | Google Play build (`FLAVOR=play`) | GitHub build (`FLAVOR=github`) |
|---|---|---|
| Jobs you apply to yourself, with the app's help | Yes | Yes |
| Auto apply (the app sends LinkedIn Easy Apply forms by itself) | No | Yes |
| Updates | Google Play | By itself from GitHub releases: downloads, then installs when the screen turns off |
| Background service, LinkedIn sign-in inside the app | No | Yes |

The Google Play build never sends an application by itself and never drives LinkedIn: you open each job, check the
form and send it. That is what keeps it inside Play's rules (see `PLAY_CONSOLE.md`).

## Applying with the app's help (both builds)

- **Find jobs.** Home has one LinkedIn search per job title of yours (Easy Apply only, newest first, posted within your
  setting, your work type and level), opened in the LinkedIn app, plus Google Jobs and Wuzzuf in the app's browser.
- **Collect them.** On any job, tap Share and pick *Add to JSTB* (or paste a link). LinkedIn links become their
  plain `/jobs/view/ID/` form; tracking bits are removed from other links; the share text gives the title and company.
  The same job is never added twice. Kept in `copilot.json` (`core/Copilot.java`).
- **Apply.** Home shows the next job (best fit first). Its sheet has:
  - *Apply on LinkedIn* (opens the LinkedIn app) or *Open the form here* (the app's browser, for company sites);
  - your answers, one tap copies one (salary as a number, notice period, years of experience, links...);
  - *A question the form asks*: type it, get the answer from your info, else from the AI (`/api/fill`);
  - a cover letter for this job (AI), and a note.
- **The app's browser** (`MainActivity.showBrowser`): *Fill the form* runs `assets/fill.js` on the page, the engine
  answers each field the way auto apply does (`core/Filler.java`: your name and email from your account, then your
  setup answers and info, then the AI) and the fields turn green. A form inside a frame from Greenhouse, Lever,
  Workable... is opened on its own. When the site asks for a file, the app offers your CV under its own name. When
  the page loads, its text rates the fit with your CV (once per job) and names the job.
- **Did you apply?** Back from LinkedIn (or the site), the app asks. *Yes* stamps it Sent, counts it toward your daily
  goal and offers the next job.
- **Applied** lists every application (yours and auto apply's) with its stage: Applied, Interview, Offer, Rejected.

## Auto apply (GitHub build)

Searches LinkedIn for your job titles, scores each job against your CV with the AI, fills Easy Apply forms in the
background, asks you questions it cannot answer (with suggestions from your CV), and gives you a summary at the end.
LinkedIn runs inside the app in a page drawn on a private virtual display; a foreground service keeps it going with the
screen off. Details: `core/Bot.java`, `core/LinkedIn.java`, `assets/li.js`.

It keeps going until you press Stop: after the last job title it waits `SEARCH_EVERY_MIN` (10 minutes by default,
Settings > How auto apply works > Search again after) and searches again.

You say yes before anything is sent (`APPROVE_FIRST`, on by default; Settings > How auto apply works):

- Each form is filled up to Submit and closed without sending; the job's status is `ready` and its answers are kept.
- One notification (`Notifs.ready`, channel "Applications to send") lists them: **Apply** / **Skip** for one,
  **Apply all** / **See them** for more (`app/ActionReceiver.java`). Home shows the same list under **Ready to send**
  (`/api/ready`).
- Apply makes it `approved`: a running bot sends it next (in the wait between rounds too), at your pace (the hourly and
  daily limits count); when the bot is not running, a short run starts that only sends those (`Bot.Options.sendOnly`).
  It is not scored again. Skip makes it `user_skipped`.
- Meanwhile the bot goes on preparing the next jobs (no pacing waits while preparing; the pace is for sending), until
  as many wait as your hourly limit (at least 3, `Bot.readyCap`); then it waits for your answers before it prepares
  more (a one-round run ends its round there).

New questions (`NEW_QUESTIONS`, Settings > How auto apply works): `unsure` (default) has the AI answer when it is sure
(`Ai.sureAnswer`, sure at 75 or more of 100: the CV, the notes or your answers say it) and asks you the rest, with its
guess among the suggestions; `ask` asks you every new question; `ai` lets the AI answer all. Your answers are kept and
used next time, in every mode.

How form questions are answered (`core/Profile.java`, both builds):

- Years of experience with something in your CV: the years since you graduated. Not in your CV: your info, then the
  AI if it is sure, else you (or the AI, or you only, per the setting).
- "How many" questions get a number only. Notice period in days (weeks or months when the question asks).
- Salary per month in your currency; converted with today's rate when the question names another currency, x12 when it
  asks per year, and the matching range when the form offers ranges.

Your info (`facts.json`, `core/Facts.java`): everything forms ask about you, in 12 categories, matched to new wordings of
the same question (exact, nearly the same words, or by meaning through the AI).

## Your data in your account (both builds)

`core/Sync.java` keeps the phone's data the same as the account's on the server: the setup answers, the settings that
follow you (not the update ones), your info, your jobs, auto apply's job log (without each form's questions) and the
CV. Each item is a record; `sync.json` remembers what was synced and the server's revision of it.

- A sync takes what changed on the server (from another phone, or from Google Drive through the server), then sends
  what changed here. An item changed on both sides: the newer change wins on both.
- It runs a few seconds after you change something, every 2 minutes while the app is open (every minute while auto
  apply runs), after signing in, and with Sync now.
- A new phone: sign in, and the setup is skipped when the account has a CV and finished setup ("Welcome back").
- Sign in with Google (3.2, `Account.googleStart` / `googleFinish`): the app opens the server's Google page in the
  browser with a PKCE challenge; the server's page after Google opens `autoapply://auth?code=...`; the app trades the
  code and its verifier for a sign-in (`MainActivity` then calls `window.aaGoogle`). The same consent connects Google
  Drive, so a new account made with that Google account gets its data from Drive first. Email accounts made earlier
  still sign in with their password.
- Another account on the same phone: this phone's data is replaced by that account's (the first account keeps its own).
- Google Drive: Settings > Your data. The app opens Google's page in the browser; after that the server does the work
  (see `server/README.md`). Back in the app, it syncs (the server reads Drive, then the phone takes what changed).

## The server

- `core/Account.java`: sign up / in / out / delete. Only a sign-in token is kept on the phone. Every request says which
  build and version this is (`X-App-Flavor`, `X-App-Version`).
- `core/Ai.java` sends AI requests to the server (`/v1/ai/chat`) with a purpose; the server holds the OpenRouter key.
  Every account gets the AI for the setup, forms and cover letters (purpose `help`, within the admin's daily AI
  limit for accounts without Pro); auto apply and its notifications need Pro.
- `core/Reporter.java` sends the server what the app does: the activity lines you see in the app, each application
  (yours and auto apply's, with its channel and stage). While auto apply runs: every 30 seconds and right after each
  application, and the reply can stop the run (the admin's Stop, Pro turned off). Otherwise every couple of minutes
  when there is something new. What cannot be sent waits in `report_queue.json`.
- The server's answers also carry the admin's announcement (shown on Home) and the oldest version it accepts: an older
  app shows only an *Update JSTB* screen (Google Play, or the GitHub release).

## Build

Needs a JDK, the Ubuntu packages `aapt aapt2 dalvik-exchange zipalign apksigner`, the Android 14 `android.jar`
(`ANDROID_JAR`) and, for Google Play, `bundletool.jar` (`BUNDLETOOL`).

```bash
export KEYSTORE=/path/to/autoapply.jks KS_PASS=...    # your signing key (never in this repo)
./build.sh                                           # out/github/AutoApply.apk  (updates itself from GitHub)
SERVER_URL=https://DOMAIN FLAVOR=play ./build.sh     # out/play/AutoApply.aab    (for Google Play)
VERSION_CODE=108 VERSION_NAME=3.2 ./build.sh         # a given version
```

GitHub Actions builds both on every push and publishes a release for every `v*` tag (see the top README). The Play
build leaves out `assets/li.js`, the background service and its permissions, and the self-updater
(`flavors/play`). Both target Android 16 (API 36).

## Tests

- `test/run.sh [scenario|all]`: the engine on the JVM against LinkedIn mocks and the real server (`../server`).
- The screens: Playwright drives `assets/ui` with the real engine behind it (`test/UiServer.java`, `-Dflavor=play` or
  `github`).

## Layout

| Path | What it is |
|---|---|
| `src/com/autoapply/bot/core/` | The engine, plain Java (also runs on a PC for tests) |
| `src/com/autoapply/bot/app/` | Android: the activity and its browsers, background service, notifications, file sharing |
| `flavors/github/`, `flavors/play/` | What differs between the builds |
| `assets/ui/` | The app's screens (HTML/CSS/JS), pdf.js to read the CV, the Figtree font (OFL) |
| `assets/fill.js` | Fills a company's application form in the app's browser |
| `assets/li.js` | Reads and fills LinkedIn's pages (GitHub build only) |
