# Masarif

An Android app that reads your bank alerts on the phone, turns them into a spending
picture, and tells you where the money is actually going.

Built for a Galaxy S24 Ultra, but it runs on anything with Android 8.0 or newer.

---

## Getting the APK

The GitHub Actions workflow in this repo builds the app. Push to `main` and it
produces `Masarif-release.apk` under **Releases → latest**, and as a build
artifact under the **Actions** tab.

Open that release page in Chrome **on the phone**, download the APK, tap it, and
allow "Install unknown apps" when prompted. The app is signed with the standard
Android debug key so it installs without any keystore setup.

To build locally instead, open the project in Android Studio and run
**Build → Build Bundle(s) / APK(s) → Build APK(s)**, or:

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

## First run

The app walks you through three things, in this order:

1. **Notification access** — reads purchase alerts pushed by banking and wallet
   apps. Android has no permission popup for this; it opens a system settings list
   where you switch Masarif on.
2. **SMS access** — most UAE banks still send transaction alerts by SMS.
3. **Import history** — reads the last 12 months of bank SMS already in your
   inbox, so the charts are useful on day one instead of in a month.

One Samsung-specific note: One UI aggressively sleeps background apps. If capture
goes quiet after a few days, set Masarif to **Unrestricted** under Battery in its
app settings. There is a shortcut for this in Settings.

---

## How it reads an alert

There are no hardcoded bank templates in this app, on purpose. A parser built on a
guessed template breaks the first time the bank changes a comma. Instead:

**1. Heuristic extraction.** Amount, currency, direction, merchant, card last-4 and
available balance are each found independently, using the structural shapes bank
alerts share. This is what makes the app work on a bank whose exact wording nobody
wrote down. Some of the fiddly parts it already handles:

- An amount glued to its currency (`AED89.00`) — there is no word boundary between
  `D` and `8`, which naive patterns miss.
- Telling the transaction amount from the available balance in the same message,
  without mistaking `AED 250 spent. Available balance AED 5,000` for a AED 5,000
  purchase.
- `credit card` describing the instrument, not a credit to your account.
- One-time codes, balance enquiries and marketing, all rejected.

**2. The Rule Lab.** Anything it cannot read lands in a queue. Tap a message, tap
the words that hold the amount and the merchant, and the app writes an exact regex
and re-runs it over everything still queued. A rule learned from one message reads
every future message of that shape.

**3. Deduplication.** Banks often send the same purchase twice — once by SMS, once
as an app push. Same amount, card and direction within four minutes is treated as
one event.

## What it works out

All of this is computed on the device from your own transactions:

- Spend against income, with a straight-line projection of where the period lands
- Budget period anchored to payday rather than the 1st, if you want
- Per-category budgets with pace warnings before you breach them
- Recurring charges, detected by cadence and amount stability — a subscription is
  three-plus charges at a consistent interval, not just a shop you visit monthly
- Category spikes against the median of previous periods
- Bank fees, small-transaction leakage, merchant concentration, savings rate
- Salary detected automatically from recurring credits

The AI coach is optional and off by default. With it on, it sends category totals
(and merchant names only if you switch that on separately) to your own API key.
Card numbers, balances and raw alert text are never sent.

## Privacy

No account, no server, no analytics. Everything lives in a SQLite database on the
phone. The only network request the app can make is the AI coach, using a key you
provide, and the app works fully without it. The API key is encrypted with a key
held in the Android Keystore. Backups are excluded so the database never leaves
the device.

Because it reads SMS, this app cannot be distributed through the Play Store —
Google restricts those permissions to default SMS handlers. That is why you
sideload it.

---

## Project layout

```
data/         SQLite storage, parse engine, rule builder, categorizer, settings
capture/      Notification listener, SMS receiver, inbox backfill
analysis/     Aggregation, subscription and salary detection, insight engine, AI coach
ui/           Compose screens, Canvas-drawn charts, theme
```

No ORM, no dependency-injection framework, no charting library, no HTTP client.
The dependency list is Compose plus AndroidX essentials; storage is plain
`SQLiteOpenHelper` and the charts are drawn on a `Canvas`. That keeps the build
fast and the moving parts few.

## Tests

The parser, rule builder, categorizer, analytics and insight engine are pure
Kotlin and covered by JVM unit tests — no emulator needed. CI runs them on every
push, and the build fails if they fail.

```bash
./gradlew :app:testDebugUnitTest
```

Several tests are regressions for bugs found during development and are labelled
as such in the source.
