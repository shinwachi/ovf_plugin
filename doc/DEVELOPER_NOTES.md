# Developer notes

Working history of non-obvious bugs, design decisions, and surviving quirks
that shaped this plugin. Skim before touching the areas below — several were
painful to diagnose and easy to re-break.

Two source trees mirror the same code, targeting different KNIME runtimes:

- `org.ovf.serverconnector/` — KNIME 5.x (Eclipse 4.31, Java 17)
- `org.ovf.serverconnector.413/` — KNIME 4.1.x (Eclipse 4.7, Java 8)

Every fix below was mirrored in both trees unless noted otherwise.

---

## 1. Runtime behavior bugs

### 1.1 Multi-select delete only removed one item

**Symptom:** selecting several rows in Server Explorer and hitting *Delete*
removed only the first one; the rest stayed.

**Root cause:** `DeleteAction.run` used
`viewer.getStructuredSelection().getFirstElement()`, so a multi-row selection
collapsed to one node before the delete loop even started.

**Fix:** iterate the full `IStructuredSelection`, collect every `WorkflowNode`,
process each one, and refresh once at the end. The confirmation prompt now
adapts: single-row shows the name, multi-row shows a count. See
`actions/DeleteAction.java`.

### 1.2 Multi-select drag & drop only transferred one workflow

**Symptom:** dragging several selected workflows onto the server dropped only
one of them.

**Root cause:** `WorkflowDropAdapter.getDraggedNode()` returned a single node
picked from `LocalSelectionTransfer`.

**Fix:** renamed to `getDraggedNodes()` returning a `List<WorkflowNode>`;
`validateDrop` verifies all sources share the same location and none is a
self/descendant target; `performDrop` iterates sources. See
`dnd/WorkflowDropAdapter.java`.

### 1.3 Seeded "Default" server hijacked fresh installs

**Symptom:** first launch on a machine outside the plugin author's LAN would
show a **stuck "Connecting…"** indicator forever. Adding a Local Drive server
via preferences did nothing — the local files never appeared.

**Root cause:** the activator called `ServerConfigStore.seedDefaultIfEmpty()`
which wrote a hard-coded `http://knimeserver.wachilab.com` entry to the
preference store on first launch. On any other network the URL was
unreachable, DNS or connect blocked for the OS default timeout (30 s+ on
Windows), and the status label never advanced. Meanwhile
`ServersPreferencePage.performOk` only auto-activates a newly added server
if `servers.size() == 1` — the freshly added Local share was #2, so it sat
inactive behind the unreachable default. To the user this looked like "I
added my folder and the plugin refuses to show it."

**Fix:**

- Removed `seedDefaultIfEmpty()` from `ServerConnectorActivator.start()`.
- `ServerConfigStore.getActive()` returns `null` instead of synthesizing a
  fallback. Callers now handle the null case explicitly.
- `ServerClient` tolerates a null `ServerConfig`: `browse()` returns an
  empty list, `isHealthy()` returns `false`, destructive ops throw a
  clear `"No server configured"` `IOException`, and `isUnconfigured()`
  is exposed so callers can branch. See `client/ServerClient.java`.
- `ServerExplorerView.checkServerConnection()` short-circuits when no
  server is configured and sets the status label to
  `"No server configured — click ⚙ to add one"` instead of running an
  HTTP check that would just hang.
- `onServerConfigChanged` is null-safe on both sides of the equality check.

Upgrading users still have the seeded entry in their prefs; document the
manual delete step or ship a one-time migration.

### 1.4 Upload / download blocked the UI thread — KNIME "froze" on SMB targets

**Symptom:** dragging a workflow from KNIME Explorer to a Server Explorer
tree whose active server is a **Local Drive** pointing at an SMB / NFS
mount would freeze the entire KNIME UI for tens of seconds while the
transfer ran. Same on the reverse direction. On a truly local disk the
same operation was fast enough to be invisible, so the bug went
unnoticed until we added an SMB target.

**Root cause:** `UploadAction.uploadNode()` and
`DownloadAction.downloadNode()` did the whole zip + write cycle inline
on the SWT UI thread. `client.upload(target, zipData)` for a
`LocalServerBackend` calls `deleteRecursive(target)` followed by
`ZipHelper.unzipToDirectory(zipData, target)` — every file operation is
a separate SMB round-trip. A 100-file workflow = 100 round-trips
serialised on the UI thread. The class comment on
`WorkflowDropAdapter` even claimed *"Heavy work runs in a background
Job"* but only the `move*` helpers actually did so; the two most common
operations (upload, download) did not.

**Fix:** wrap the zip+I/O portion of both actions in a `Job` with
`setUser(true)` so a cancellable progress dialog appears. Dialogs
(rename prompt, success message) still run on the UI thread; the
long-running byte-shuffle runs off-thread. Success handlers marshal
back to the UI via `Display.asyncExec`. Errors do the same for the
error dialog. See `actions/UploadAction.java::uploadNode` and
`actions/DownloadAction.java::downloadNode`.

Fix mirrored in both source trees.

### 1.5 Cross-client server changes invisible without manual refresh

**Symptom:** when two KNIME instances point at the same backend, changes made
by instance A (upload / delete / rename) don't appear in instance B's tree
until the user manually hits Refresh or triggers a browse.

**Root cause:** the content provider was purely pull-based; nothing polled
the backend for changes.

**Fix:**

- New Flask endpoint `/api/v1/state` returns a SHA-1 of a sorted
  `(relpath|kind|mtime[|size])` walk of the workflow tree. Cheap to
  compute; identical bytes iff identical tree state.
- Local backend implements the same signature using `File.lastModified()`
  and `File.length()` — see `LocalServerBackend.stateSignature()`.
- `ServerExplorerView` runs a system `Job` that polls
  `client.stateSignature()` at **10 s when the view is focused, 60 s
  otherwise**, and refreshes the server branch only when the signature
  changes. Focus is tracked via `IPartListener2.partActivated /
  partDeactivated`. See `startStatePoller()` in
  `views/ServerExplorerView.java`.
- Cancellation is handled in `dispose()` and on active-server change
  (baseline is reset so the next tick doesn't spuriously trigger a
  refresh against the new backend).

---

## 2. Windows-specific fixes

### 2.1 InvalidPathException on the workspace URL path

**Symptom on Windows:** the whole view failed to render. Eclipse showed
*"Failed to create the part's controls"* and the widgets that had been
partially built before the crash appeared **arranged horizontally** inside
Eclipse's error wrapper. The status label was frozen at its initial
`"Connecting…"` text — even after the seeded-default bug was fixed. The
error details showed:

```
java.nio.file.InvalidPathException: Illegal char <:> at index 2:
    /C:/Users/shinw/knime-workspace
    at sun.nio.fs.WindowsPathParser.normalize(...)
    at java.nio.file.Paths.get(...)
    at org.ovf.serverconnector.views.ServerExplorerView.startWorkspaceWatcher(...)
    at org.ovf.serverconnector.views.ServerExplorerView.createPartControl(...)
```

**Root cause:** `Platform.getInstanceLocation().getURL().getPath()` returns
the URL-form path with a leading slash before the drive letter on Windows:
`/C:/Users/<user>/knime-workspace`. Passing that string to `Paths.get()`
throws because `:` is not valid at index 2 in a Windows filesystem path
(the parser expects `C:\...`). The exception fired inside
`startWorkspaceWatcher()`, which is called from `createPartControl()`, so
the whole view construction aborted mid-way — the earlier fixes for the
seeded default weren't even reached.

The "horizontal layout" symptom was a **red herring**: `parent.setLayout(new
GridLayout(1, false))` is correct and produces vertical stacking on all
platforms; what the user saw was the half-built status strip + search row
lying inside Eclipse's error part composite, which uses a different layout.

**Fix in `ServerExplorerView.createPartControl()`:**

```java
URL locUrl = Platform.getInstanceLocation().getURL();
File dir;
try {
    dir = new File(locUrl.toURI());   // correct URL -> platform path
} catch (Exception uriEx) {
    String raw = locUrl.getPath();
    if (raw.length() > 3 && raw.charAt(0) == '/'
            && raw.charAt(2) == ':'
            && File.separatorChar == '\\') {
        raw = raw.substring(1);        // "/C:/foo" -> "C:/foo"
    }
    dir = new File(raw);
}
workspacePath = dir.getAbsolutePath();
```

`new File(url.toURI())` is the platform-correct URL-to-path conversion.
The catch branch handles the rare case where the URL isn't a valid URI
(unescaped spaces, etc.); it strips the leading slash before a drive
letter only when `File.separatorChar` is `\`, so it's a no-op on
Linux/macOS.

**Defensive follow-up in `startWorkspaceWatcher()`:** wrap `Paths.get()`
in `try/catch`. The watcher is a background convenience, not a
correctness requirement — a malformed path should skip the watcher, not
tear down the view.

**Lesson:** never let a background convenience abort `createPartControl`.
Wrap risky calls that aren't strictly required for the view to function.

---

## 3. Bundle rename: `org.knime.*` → `org.ovf.*`

**Motivation:** the plugin was originally packaged as
`org.knime.serverconnector` with display name *"KNIME Server Connector"*.
Both were misleading — `org.knime.*` is reserved for KNIME's own bundles
(installing something in that namespace as a third party looks like
squatting), and the display name made this look like an official KNIME
product in *Installed Software*.

**Rename scope:**

- **Bundle-SymbolicName:** `org.knime.serverconnector` → `org.ovf.serverconnector`
- **Bundle-Name (display):** `"KNIME Server Connector"` → `"OVF Server Explorer"`
- **Bundle-Vendor:** `"KNIME User"` → `"OVF"`
- **Feature ID:** `org.knime.serverconnector.feature` → `org.ovf.serverconnector.feature`
- **Feature label:** `"KNIME Server Connector"` → `"OVF Server Explorer"`
- **Java packages:** `org.knime.serverconnector.*` → `org.ovf.serverconnector.*`
- **Directory layout:** `org.knime.serverconnector/` → `org.ovf.serverconnector/`
  (plus `.413` variant)
- **Distribution filenames:** `knime-serverconnector-*.zip` →
  `ovf-serverconnector-*.zip`
- **p2 category label:** `"KNIME Server Connector"` → `"OVF Server Explorer"`

**What is deliberately NOT renamed:**

- The view name `"Server Explorer"` (still shows under KNIME's own
  `KNIME Views` category so it sits next to KNIME Explorer).
- The view's Show View category (`org.knime.workbench.ui.category`) —
  intentional reuse of KNIME's category so the view appears in the same
  *Window > Show View > Other > KNIME Views* group as KNIME Explorer.
- `File > Preferences > KNIME > OVF > Servers` — the preference page
  nests under the KNIME root because that's where KNIME conventionally
  puts prefs; only the top-level page name is OVF-branded.

**p2 upgrade implication:** changing Bundle-SymbolicName makes the old
and new bundles look like distinct extensions to p2. Users of any
pre-rename build must **Uninstall** via
*Help > About > Installation Details* and then install the OVF build.
There is no in-place upgrade.

---

## 4. Packaging & distribution

### 4.1 `jar cf` destroys the OSGi manifest

**Symptom:** view fails to load with `java.lang.Exception` at
`ViewReference.createErrorPart`; workspace log shows
*"Removing part descriptor … Points to the invalid
'bundleclass://…/CompatibilityView' class"*.

**Root cause:** the build script ran `jar cf out.jar .` from a classes
directory. `jar cf` (no `m`) writes a **default** `META-INF/MANIFEST.MF`
containing only `Manifest-Version: 1.0` — the OSGi headers we carefully
put in `META-INF/MANIFEST.MF` (Bundle-SymbolicName, Bundle-Activator,
Import-Package, Require-Bundle) were silently overwritten. Eclipse
recognized the jar as "some jar in plugins/" and refused to instantiate
its class references.

**Fix:** always use `jar cfm out.jar META-INF/MANIFEST.MF -C classes .` (or
`jar cfm` from inside the staging directory). This preserves the custom
manifest.

### 4.2 Icons must be **inside** the bundle jar

**Symptom:** `platform:/plugin/org.ovf.serverconnector/icons/server.png`
resolution fails with `java.io.IOException: Unable to resolve plug-in
"platform:/plugin/…"`.

**Root cause:** first-build script only zipped classes + META-INF + plugin.xml
into the bundle. Eclipse looks up `platform:/plugin/<sym>/…` inside the
bundle's own JAR; the `icons/` directory has to be included, not left
outside as a sibling directory.

**Fix:** copy `icons/` alongside `META-INF/` and `plugin.xml` into the
classes staging directory before `jar cfm`. The current build script does
this.

### 4.3 URL alignment via traefik

Originally the plugin's default fallback URL was
`http://knimeserver:8910` — the docker-compose service name that only
resolves inside the compose network. That value was baked into three
places: the plugin's Java code (`ServerClient` constructor default),
`server/run_server.sh` (KNIME 5.x JVM `-D` arg), and `knime413/run.sh`
(4.1.x JVM `-D` arg).

Fixed by aligning all three sites on the traefik convention already in
use for the xpra routes: `http://knimeserver.wachilab.com`. Same DNS
resolution as `xpra.knimeserver.wachilab.com` and
`xpra.knime413.wachilab.com` — resolvable from any container on the
`traefik` network, and browsable from the outside as well.

### 4.4 Distribution channels

Four artifacts are published for each KNIME version. All live in `dist/`:

| Channel | 5.x path | 4.1.x path |
|---|---|---|
| Dropin zip | `dist/ovf-serverconnector-5.x-<ver>.zip` | `dist/ovf-serverconnector-4.1.x-<ver>.zip` |
| p2 update-site archive | `dist/ovf-serverconnector-5.x-<ver>-updatesite.zip` | `dist/ovf-serverconnector-4.1.x-<ver>-updatesite.zip` |
| Live update-site URL | `http://updatesite.wachilab.com/5x/` | `http://updatesite.wachilab.com/4.1.x/` |
| Raw downloads mirror | `http://updatesite.wachilab.com/downloads/` | same |

The **live URL** is served by the `updatesite` docker service (nginx +
unzip on start; entrypoint at `updatesite/entrypoint.sh` extracts the
current `-updatesite.zip` from a read-only `dist/` mount). Restart the
container after `dist/` changes and it re-extracts.

The **p2 archive** is built by two Eclipse publishers running inside the
container whose Eclipse matches the target runtime; commands and
`feature.xml` / `category.xml` inputs are archived at
`dist/p2-sources/`.

### 4.5 Test VMs

`testvm5/` and `testvm413/` are minimal stock-KNIME xpra containers with
**no plugin pre-installed** and `dist/` mounted read-only at `/install`.
They exist for manually rehearsing the end-user install flow (dropin OR
updatesite archive) before shipping. Handy for reproducing what a
first-time installer sees.

Access via `xpra.testvm5.wachilab.com` / `xpra.testvm413.wachilab.com`
in a browser. Uninstall/reinstall cycles run against `/opt/knime` inside
the container.

---

## 5. p2 upgrade mechanics: Bundle-Version bump is mandatory

**The gotcha that cost the most session time.**

When a bugfix is published to the update site under the **same
Bundle-Version** as a build the user already has installed, KNIME's p2
sees the on-disk bundle as identical to what's advertised and skips the
fetch — **even after the user runs Uninstall and reinstalls**. The p2
artifact cache retains the old bytes, and the reinstalled bundle is the
old broken one. The Windows workspace path crash reproduced through
three rebuild cycles for exactly this reason.

**Rule:** any code fix that needs to reach existing installs via p2 must
bump `Bundle-Version` in `META-INF/MANIFEST.MF`. Also bump:

- Feature version in `dist/p2-sources/{5x,413}/feature.xml`
- Feature URL and version in `dist/p2-sources/{5x,413}/category.xml`
- Distribution filenames if you keep the version in the filename
- Bundle jar filename inside the p2 repo (`_<ver>.jar`)

For pure packaging / release-metadata changes (INSTALL.txt tweaks,
README, no bytecode change), no version bump is needed.

---

## 6. Surviving known quirks

### 6.1 KNIME 5.x URL install (HTTPS) — resolved via a private CA

**Original symptom (pre-fix):** *"Unable to read repository … cannot
read content.xml"*. Log showed `Connection to
https://updatesite.wachilab.com/5x/p2.index failed on
(certificate_unknown) PKIX path building failed`.

**Root cause:** Eclipse 4.31's p2 transport tries HTTPS first as a
security-upgrade heuristic even when given an `http://` URL. Our
traefik was serving a self-signed cert on port 443, PKIX validation
failed against Java's trust store, and the transport did **not** fall
back to HTTP — the whole repository load aborted. The 4.1.x transport
is older and lacks this heuristic, so 4.1.x URL installs work over
HTTP without needing TLS.

**Why not Let's Encrypt:** `updatesite.wachilab.com` resolves to the
private IP `192.168.1.5`, so LE's HTTP-01 challenge can't reach the
host from the internet. The DNS is also manually managed with no API,
so LE's DNS-01 challenge is impractical. Both flavors ruled out.

**Chosen fix — private CA via [mkcert](https://github.com/FiloSottile/mkcert):**

1. Generated a local root CA at `~/.mkcert/rootCA.pem` (10-year
   validity).
2. Issued a `*.wachilab.com` + `wachilab.com` leaf cert signed by that
   CA, valid until 2028-10-07.
3. Replaced the self-signed pair in `homelab_traefik/certs/` with the
   mkcert-issued leaf; traefik's file provider re-loaded automatically.
4. Added a HTTPS router label pair to the `updatesite` compose service
   (`websecure` entrypoint, `tls=true`) so the same nginx serves both
   schemes.
5. Bind-mounted the root CA into the container and taught the nginx
   entrypoint to publish it at `/rootCA.crt` (with matching MIME type
   in `nginx.conf`), so a Windows client can grab and trust the CA in
   one browser hit.

**Windows client setup (one-time per box):**

1. Browse to `http://updatesite.wachilab.com/rootCA.crt`.
2. When the file downloads, right-click → *Install Certificate...*
3. *Local Machine* → *Place all certificates in the following store*
   → **Trusted Root Certification Authorities** → Finish.
4. **Also import into KNIME's bundled JRE truststore** so KNIME's p2
   sees it (Windows-level trust doesn't propagate into Java): from an
   admin cmd/PowerShell, replacing paths for your install:

   ```powershell
   cd "C:\Program Files\KNIME\plugins\org.knime.binary.jre.win32.x86_64_<ver>\jre\bin"
   .\keytool -importcert -trustcacerts -noprompt -alias mkcert-wachilab `
       -file "$env:USERPROFILE\Downloads\rootCA.crt" `
       -keystore ..\lib\security\cacerts -storepass changeit
   ```

After that, `https://updatesite.wachilab.com/5x/` works in *Install
New Software > Add > Location*. Every other `*.wachilab.com` service
(xpra tabs, etc.) also becomes properly TLS-trusted with no further
work.

**Validated:** fresh KNIME 5.x container, mkcert CA imported into
KNIME's bundled JRE cacerts, `p2.director` installs from
`https://updatesite.wachilab.com/5x/` cleanly (~6 seconds).

**Fallback still available:** the archive install path
(`ovf-serverconnector-5.x-<ver>-updatesite.zip` from `/downloads/`,
*Install New Software > Add > Archive*) does not touch HTTPS and
therefore doesn't need the CA installed. Recommend it for one-off
installs where importing the CA isn't worth the setup cost.

### 6.2 4.1.x doesn't apply `perspectiveExtension` to cached perspectives

**Symptom on 4.1.x:** after installing via the update site into an
already-open KNIME, the Server Explorer view doesn't appear stacked
next to KNIME Explorer (as declared in `plugin.xml`); the workbench
places it in whatever pane it feels like, or doesn't render it at all
until the user hits *Window > Reset Perspective*.

**Root cause:** the e3-compat workbench in Eclipse 4.7 doesn't re-apply
`perspectiveExtension` contributions to perspectives it has cached. The
runtime `tryCoStack()` code in `ServerConnectorActivator` uses the e4
`EModelService` API to reparent the view's `MPart` into KNIME Explorer's
`MPartStack`, which works on 5.x but not on the e3-compat 4.1.x
workbench.

**Workaround:** on 4.1.x, users either *Window > Reset Perspective* to
force the extension to apply, or drag the Server Explorer tab into the
KNIME Explorer pane by hand (it sticks across restarts). The dropin zip
INSTALL.txt documents both.

### 6.3 Preferences file is keyed on Bundle-SymbolicName

Old-build prefs from `org.knime.serverconnector` were stored under
`<workspace>/.metadata/.plugins/org.eclipse.core.runtime/.settings/org.knime.serverconnector.prefs`.
After the rename to `org.ovf.serverconnector`, the new bundle looks in
`org.ovf.serverconnector.prefs` and does not see the old servers list.
This is intentional (namespaces are separate) but means anyone
upgrading from a pre-rename build re-configures from scratch.

---

## Appendix: fix-index by file

| File | What it fixes |
|---|---|
| `views/ServerExplorerView.java::createPartControl` | Windows workspace-path crash (§2.1) |
| `views/ServerExplorerView.java::startWorkspaceWatcher` | Defensive catch on bad path (§2.1) |
| `views/ServerExplorerView.java::checkServerConnection` | No-server call-to-action (§1.3) |
| `views/ServerExplorerView.java::onServerConfigChanged` | Null-safe equality (§1.3) |
| `views/ServerExplorerView.java::startStatePoller` | Cross-client refresh polling (§1.5) |
| `preferences/ServerConfigStore.java::getActive` | Returns null when unconfigured (§1.3) |
| `ServerConnectorActivator.java::start` | Seed removed (§1.3) |
| `client/ServerClient.java` | Tolerates null cfg (§1.3) |
| `client/HttpServerBackend.java::stateSignature` | Signature endpoint client (§1.5) |
| `client/LocalServerBackend.java::stateSignature` | Same for filesystem backend (§1.5) |
| `actions/DeleteAction.java::run` | Multi-select iteration (§1.1) |
| `actions/UploadAction.java::uploadNode` | Off-UI-thread upload (SMB freeze) (§1.4) |
| `actions/DownloadAction.java::downloadNode` | Off-UI-thread download (SMB freeze) (§1.4) |
| `dnd/WorkflowDropAdapter.java::performDrop` | Multi-select drag (§1.2) |
