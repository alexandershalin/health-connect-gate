# Privacy policy

*Health Connect Gate* reads the health data you allow through Android Health Connect and sends it **only to the server you
configure yourself**. The author of the app operates no server, receives no data and has no access to yours.

## What the app reads

Only what you choose in the app and grant in Health Connect. The app groups Health Connect's record types into seven categories (activity, heart and vitals,
body measurements, sleep and mindfulness, nutrition and hydration, cycle tracking, sexual activity); the two sensitive ones - cycle tracking and sexual activity -
are off until you turn them on. The app requests and reads only the chosen categories, plus read access to history older than 30 days and to data in the
background. You can change the choice at any time and revoke any permission in Health Connect.
Each record is sent with its Health Connect metadata (record id, time stamps, the package name of the app that wrote it, device manufacturer and model).

## Where the data goes

* To the domain you type into the app, over HTTPS. Nothing is sent anywhere else.
* No analytics, advertising, tracking or crash-reporting services are used, and there are no third-party SDKs that transmit data.
* What the server does with the data (storage, retention, sharing) is decided by whoever runs that server - normally you.

## What stays on the phone

In the app's private storage: the server domain, sign-in tokens (stored unencrypted, see the README's limitations), the state of the
synchronisation, and a small diagnostics outbox (phases, error messages and stack traces). The diagnostics never contain health
records or tokens (tokens are redacted); they are uploaded to your server and then removed from the phone. The app keeps no copy
of your health records. Android backup is disabled for the app.

## Your control

* Stop synchronisation by turning categories off, signing out in the app (removes the sign-in from the phone and stops background sync), revoking the permissions in Health Connect, or uninstalling the app (this also deletes its local data).
* Data already uploaded lives on your server; delete it there.
* The app has no accounts and no profile; it does not identify you to the author.

## Children

The app is not directed at children.

## Changes and contact

Changes to this policy are visible in the repository history. For questions that do not concern a security vulnerability, open an issue
without personal or health data; for vulnerabilities use the private reporting described in [SECURITY.md](SECURITY.md).
