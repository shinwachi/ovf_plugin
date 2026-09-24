# OVF Server Explorer (OVF Plugin)

An Eclipse / KNIME view that browses a workflow share on a remote HTTP
server or a local filesystem mount, with upload, download, drag-and-drop,
multi-select operations, and cross-client auto-refresh.

The view ("Server Explorer") co-stacks as a tab next to **KNIME Explorer**
in the Modeller perspective.

## Install

Pre-built binaries are hosted on GitHub Pages via a separate distribution
repo, [`ovf-updates`](https://github.com/shinwachi/ovf-updates). Landing
page and full instructions:

**<https://shinwachi.github.io/ovf-updates/>**

### Update-site URL (recommended)

Point KNIME at the URL for your version — no download step, in-place
updates on future releases:

| KNIME | URL to paste into *Install New Software > Add > Location* |
| ----- | --------------------------------------------------------- |
| 5.x   | `https://shinwachi.github.io/ovf-updates/5x/`             |
| 4.1.x | `https://shinwachi.github.io/ovf-updates/4.1.x/`          |

Steps:

1. In KNIME: *File > Install KNIME Extensions...*
   (or *Help > Install New Software...*).
2. Click **Add...** next to *Work with:*.
3. Paste the URL, name it "OVF Server Explorer", click **Add**.
4. Expand the **OVF Server Explorer** category, check the feature,
   Next, accept the license, Finish, restart when prompted.

TLS is handled natively by GitHub's own certificate — no CA import
needed on any platform.

### Archive install (offline / air-gapped)

If your KNIME can't reach GitHub, grab the archive zip and use
*Install New Software > Add > **Archive*** instead of Location:

- All zip downloads:
  <https://shinwachi.github.io/ovf-updates/downloads/>
- Latest 5.x archive:
  <https://shinwachi.github.io/ovf-updates/downloads/ovf-serverconnector-5.x-1.0.3-updatesite.zip>
- Latest 4.1.x archive:
  <https://shinwachi.github.io/ovf-updates/downloads/ovf-serverconnector-4.1.x-1.0.3-updatesite.zip>

### Dropin (last-resort fallback)

If you can't use the GUI installer at all, the raw dropin zips are in
the same download index above (the non-`-updatesite.zip` variants).
Extract into `<knime-install>/dropins/`, then launch KNIME once with
`-clean`. Each dropin zip ships an `INSTALL.txt` with per-OS steps.

### After install

Look for a *Server Explorer* tab next to **KNIME Explorer** in the
Modeller perspective. If the tab isn't visible:
*Window > Show View > Other... > KNIME Views > Server Explorer*.

Configure a server under *File > Preferences > KNIME > OVF > Servers*.

### Build outputs (for developers)

Locally-built binaries land in `dist/` when you run the source build
described below. The GitHub Pages site above is republished from those
same artefacts by an Actions workflow in the `ovf-updates` repo. The p2
publish inputs (`feature.xml`, `category.xml`) live under
`dist/p2-sources/`.

## Building from source

Both plugin variants live as standalone Eclipse plugin projects:

| Directory                     | Targets                                | Java |
| ----------------------------- | -------------------------------------- | ---- |
| `org.ovf.serverconnector/`     | KNIME 5.x (Eclipse 4.31 / Java 17)  | 17   |
| `org.ovf.serverconnector.413/` | KNIME 4.1.x (Eclipse 4.7 / Java 8)  | 8    |

The two trees mirror the same source layout. Differences are constrained
to API-compat shims (4.1.x has explicit `IPartListener2` stubs, lacks
Java-7+ Comparator lambdas in a few spots, etc.).

To compile and package from the command line (Eclipse-free):

```
# 5.x (Java 17, KNIME 5.x bundles on -cp)
javac -encoding UTF-8 --release 17 -d out \
    -cp "<knime-5.x>/plugins/*.jar" \
    $(find org.ovf.serverconnector/src -name '*.java')
cp -r org.ovf.serverconnector/META-INF \
      org.ovf.serverconnector/icons \
      org.ovf.serverconnector/plugin.xml out/
(cd out && jar cfm org.ovf.serverconnector_1.0.0.jar META-INF/MANIFEST.MF .)

# 4.1.x: same idea with --release 8 and the 4.1.x platform jars on -cp.
```

The 4.1.x variant requires the KNIME 4.1.3 / Eclipse 4.7 platform jars
on the compile classpath. Install KNIME 4.1.3 locally and point `-cp`
at its `plugins/*.jar`.

## Features

- Browse remote (HTTP) or local-mount (filesystem) workflow shares
- Upload / download workflows as zip
- Drag-and-drop between Server Explorer and KNIME Explorer
- Multi-select delete and multi-select drag-drop
- Rename, new folder, delete, copy URL
- Search by file/folder name, with optional deep search of node titles
  and workflow annotations
- Multiple configured servers with HTTPS + self-signed support, switchable
  from the in-view status strip
- Auto-refresh on cross-client changes (10s when the view is focused,
  60s when not), driven by a cheap `/api/v1/state` content-hash poll

## Developer notes

Non-obvious bugs, design decisions, and surviving quirks are documented
in [`doc/DEVELOPER_NOTES.md`](doc/DEVELOPER_NOTES.md). Read it before
touching the view, packaging, or the update-site pipeline — several of
these were painful to diagnose and easy to re-break.

## License

MIT — see [`LICENSE`](LICENSE).
