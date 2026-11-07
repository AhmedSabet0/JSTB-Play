# Publishing JSTB on Google Play

A checklist for Play Console, with the text to paste. `DOMAIN` is your server's domain (for example
`145-241-153-177.sslip.io`).

## 0. What the Google Play build does (and does not)

The Play build (`FLAVOR=play`) is the "apply with you" app: it collects jobs you share to it, opens them in the
LinkedIn app or in its own browser, fills company application forms with your answers (you check and send them),
writes cover letters, and tracks your applications. It **never sends an application by itself, never signs in to or
drives LinkedIn, and runs nothing in the background**. The automatic LinkedIn bot exists only in the GitHub build
(`assets/li.js`, the LinkedIn driver `core/LinkedIn.java` (a stub without any LinkedIn code takes its place), the
background service and their permissions are left out of the bundle).

A test copy of the Play build that installs next to the GitHub build (own package, own name):
`APP_ID=com.autoapply.bot.playtest APP_LABEL="JSTB Play" APK=1 FLAVOR=play ./build.sh` gives
`out/play/AutoApply-play.apk`. Do not upload that one: the Play release keeps the package `com.autoapply.bot`.

About "ask before sending" (3.2, GitHub build): there the bot fills each Easy Apply form and waits for the user's
**Apply** in a notification. That is a better experience, but it does not make the bot fit for Play: the app still
signs in to LinkedIn and searches, opens and fills its pages automatically for the user, which LinkedIn's User
Agreement does not allow (no bots or automated access), and Play's Device and Network Abuse policy does not allow apps
that use a service in a way that breaks its terms. Keep the bot out of the Play build.

Still keep in mind:

- **Payments.** Pro (AI help) is switched on by you in the admin page; the app takes no payments and never says how to
  pay. Keep it that way. If people pay you for it, Play counts that as paid app functionality, which must go through
  Google Play Billing; selling outside the app while the app shows no prices or payment links ("consumption only")
  is allowed.
- **Names and logos.** No "LinkedIn" (or its logo) in the app name, icon or graphics. In the description, mention it
  only to say what the app works with, and say it is not affiliated with LinkedIn.

## 1. Developer account

- One-time fee of 25 USD and identity verification.
- **Personal accounts created after 13 November 2023** must run a **closed test with at least 12 testers who stay
  opted in for 14 days in a row** before they can apply for production.

## 2. The first upload (by hand, once)

1. The server runs (top README) and has the OpenRouter key in the admin page.
2. Get the bundle: push a tag (`git tag v3.0.0 && git push origin v3.0.0`); the release on GitHub has
   `AutoApply-3.0.0-play.aab` with your server built in. (Or build it yourself: `SERVER_URL=https://DOMAIN FLAVOR=play ./build.sh`.)
3. The bundle is signed with `autoapply.jks`, which becomes your **upload key**. Keep it private and backed up. When
   Play asks about app signing, let Google create and keep the app signing key (the default).
4. A phone that has the GitHub build must uninstall it before installing from Play (different signature).

After that first release, Actions can upload every new tag by itself: add the `PLAY_SERVICE_ACCOUNT_JSON` secret (a
service account with the *Release manager* permission in Play Console > Users and permissions) and, if you want another
track than internal testing, the `PLAY_TRACK` variable (`alpha` is closed testing, `production`).

## 3. Create the app

All apps > Create app: name (up to 30 characters, for example `JSTB: Job Applications`), default language
English (United States), App, Free. Accept the declarations.

## 4. Closed test (the 14 days)

1. Testing > Closed testing > Create track > Create new release > upload the `.aab`.
2. Testers: an email list (or a Google Group) with at least 12 people. Share the opt-in link; they must stay opted in
   for 14 days in a row.
3. In the admin page turn on **New accounts start with Pro** so testers get AI help. Turn it off afterwards.
4. After 14 days: Dashboard > Apply for production, and answer the questions about the test.

## 5. App content (Policy and programs > App content)

| Section | Answer |
|---|---|
| Privacy policy | `https://DOMAIN/privacy` |
| Ads | No |
| App access | All or some functionality is restricted: add a reviewer account with an email and a password (new accounts are made with Google, so in the admin page turn on **New accounts with an email and a password**, create the account in the app and turn its **Pro** on; keep the switch on while the app is in review, since the app shows the email form only while it is on) with the note below |
| Content rating | Category: Utility, Productivity, Communication or Other. Answer No to everything |
| Target audience | 18 and over |
| News app / Government / Financial features / Health | No / No / none / none |
| Data safety | Section 6 |
| Foreground service | None (the Play build has no foreground service) |

**App access note for the reviewer**

> Sign in with the email and password above ("Sign in" under Continue with Google; it has AI help on). The setup asks
> for a CV (any text-based PDF works) and a few
> answers. Then Home has job searches; on any job page (LinkedIn app, Chrome, or the app's own browser) tap Share and
> pick "Add to JSTB", or paste a job link on the Jobs tab. Open the job: its sheet has your answers to copy, a
> cover letter, and "Open the form here", where "Fill the form" fills a company's application form for you to check
> and send. The app never submits anything by itself. "I applied" moves the job to the Applied tab, where you track
> its stage. No LinkedIn account is needed to review the app.

## 6. Data safety answers

Check them against your final setup before you submit.

- Does your app collect or share any of the required user data types? **Yes**
- Is all of the user data collected by your app encrypted in transit? **Yes**
- Do you provide a way for users to request that their data is deleted? **Yes** > `https://DOMAIN/delete-account`
  (and Settings > Account > Delete account in the app)
- Shared with third parties: **none**. OpenRouter processes AI requests on your behalf (a service provider), which Play
  does not count as sharing. Google Drive (when a user connects it) is the user's own storage, chosen by them.

Since 3.1 the server keeps each user's data so every phone they sign in on has it (and their Google Drive, if they
connect it): the CV file and its text, the setup answers and "Your info", settings, and the job lists. That is why the
rows below say "not ephemeral" and Files and docs is collected.

| Data type | Collected | Optional? | Ephemeral? | Purposes |
|---|---|---|---|---|
| Personal info > Name | Yes | Optional | No | Account management |
| Personal info > Email address | Yes | Required | No | Account management |
| Personal info > User IDs | Yes (account ID) | Required | No | Account management |
| Personal info > Phone number, Address (city), Other info (work history, education, salary expectations from the CV and your answers) | Yes: kept in the account (synced to the user's phones and Drive) and sent to the AI to answer forms and write cover letters | Required | No | App functionality, Account management |
| Files and docs (the CV file) | Yes: kept in the account so every phone has it, and copied to the user's Google Drive when they connect it | Required | No | App functionality |
| App activity > App interactions (jobs added and applied to: title, company, link, stage, notes; the app's activity lines) | Yes | Required | No | App functionality, Analytics |

Not collected: location, contacts, photos, device IDs, crash logs, financial info. Since 3.2 new accounts are made with
Sign in with Google: Google gives the server the user's name, email and Google account ID (the rows above), and the
same consent gives the drive.file permission (only the files the app makes), kept encrypted on the server. Users with
an older email account connect Drive by choice (Settings > Your data > Connect Google Drive).

## 7. Store listing (Grow > Store presence > Main store listing)

**Short description** (80 characters max)

> Collect jobs, fill application forms in a tap, write cover letters, track replies.

**Full description**

> JSTB helps you send more job applications with less typing, and keeps track of every one.
>
> • Find jobs: one tap opens a search for each job title you want, on LinkedIn or other job sites
> • Collect them: on any job, tap Share and pick JSTB, or paste the link
> • Fill forms fast: on company sites, "Fill the form" puts in your answers and offers your CV; you check it and send it
> • Your answers, ready to paste: salary, notice period, years of experience, links... one tap copies one
> • New questions: type the question a form asks and get an answer based on your CV
> • Cover letters written for each job
> • Track every application: applied, interview, offer, rejected
> • A daily goal to keep you going
>
> You answer the questions forms ask most once, during a 3-minute setup. Your CV and answers are kept in your account,
> so every phone you sign in on has them, and in your own Google Drive. You sign in with Google; AI help is switched
> on for your account by the service.
>
> JSTB is not affiliated with, endorsed by or sponsored by LinkedIn or any job site.

**Graphics**: `icon-512.png`, `feature-graphic-1024x500.png` and phone screenshots (1080 x 1920) from
`play-store-kit` (made with made-up employers; replace them with your own when you have real ones).

**Category**: Business (or Productivity). **Contact email**: your `CONTACT_EMAIL`. **Website**: `https://DOMAIN`.

## Policy pages used for this checklist

- Target API level: https://developer.android.com/google/play/requirements/target-sdk
- Testing requirements for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Device and Network Abuse: https://support.google.com/googleplay/android-developer/answer/9888379
- Payments: https://support.google.com/googleplay/android-developer/answer/10281818
- Account deletion: https://support.google.com/googleplay/android-developer/answer/13327111
