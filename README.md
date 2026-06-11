# KNIME Server Connector (OVF Plugin)

An Eclipse / KNIME view that browses a workflow share on a remote HTTP
server or a local filesystem mount, with upload, download, drag-and-drop,
multi-select operations, and cross-client auto-refresh.

The view ("Server Explorer") co-stacks as a tab next to **KNIME Explorer**
in the Modeller perspective.

## Install

Prebuilt dropin zips are in `dist/`:

| KNIME version | Zip                                          |
| ------------- | -------------------------------------------- |
| 5.x           | `dist/knime-serverconnector-5.x-1.0.0.zip`   |
| 4.1.x         | `dist/knime-serverconnector-4.1.x-1.0.0.zip` |

Each zip contains a `serverconnector/` dropin folder and an `INSTALL.txt`
covering Linux, macOS, and Windows. The short version:

```
cd <knime-install>/dropins
unzip /path/to/knime-serverconnector-<version>-1.0.0.zip
<knime-install>/knime -clean
```

After launch, look for a "Server Explorer" tab next to **KNIME Explorer**.
If the tab isn't visible, force it open via
*Window > Show View > Other... > KNIME Views > Server Explorer*.

Configure a server URL under *File > Preferences > KNIME > Server
Connector > Servers*.

## Building from source

Both plugin variants live as standalone Eclipse plugin projects:

| Directory                     | Targets                                | Java |
| ----------------------------- | -------------------------------------- | ---- |
| `org.knime.serverconnector/`     | KNIME 5.x (Eclipse 4.31 / Java 17)  | 17   |
| `org.knime.serverconnector.413/` | KNIME 4.1.x (Eclipse 4.7 / Java 8)  | 8    |

The two trees mirror the same source layout. Differences are constrained
to API-compat shims (4.1.x has explicit `IPartListener2` stubs, lacks
Java-7+ Comparator lambdas in a few spots, etc.).

To compile and package from the command line (Eclipse-free):

```
# 5.x (Java 17, KNIME 5.x bundles on -cp)
javac -encoding UTF-8 --release 17 -d out \
    -cp "<knime-5.x>/plugins/*.jar" \
    $(find org.knime.serverconnector/src -name '*.java')
cp -r org.knime.serverconnector/META-INF \
      org.knime.serverconnector/icons \
      org.knime.serverconnector/plugin.xml out/
(cd out && jar cfm org.knime.serverconnector_1.0.0.jar META-INF/MANIFEST.MF .)

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

## License

See `LICENSE` if/when added. No license header is set on the source yet;
treat as proprietary until otherwise marked.
