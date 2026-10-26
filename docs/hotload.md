# Trails replacement preparation

From an authorized console or RCON use `trails:hotload prepare`, then
`trails:hotload status`. Preparation stops gameplay using the ordinary shutdown
owner and keeps this maintenance endpoint available. Repeat preparation observes
the same terminal drain; it does not start another close. There is no resume.
Only `ready` confirms successful cleanup of this plugin's owned resources.
`preparing` and `failed` forbid code replacement.

Keep ArcCore and other shared providers enabled until cleanup completes. For any
provider or event/API dependency, prepare and unload consumers leaf-first, then
replace their provider and fresh-load consumers in dependency order. A stopped
consumer can still retain the old API/classloader. A changed shared ABI or an
unvalidated dependency closure requires an ordinary coordinated server restart.

Use only an external loader already validated for the exact plugin/server and
its dependency closure. This endpoint neither embeds nor certifies PlugManX.
Source tests and same-instance cycles establish preparation only; acceptance
still requires two distinct-JAR/fresh-classloader cycles, durable state and a
late callback check, resource counts, versions and hashes in one server process.
