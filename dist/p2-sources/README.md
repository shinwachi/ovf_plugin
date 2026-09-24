# p2 archived-update-site sources

These `feature.xml` + `category.xml` files are the inputs that produce the
Eclipse p2 update-site archives in `dist/`:

- `ovf-serverconnector-5.x-<ver>-updatesite.zip`
- `ovf-serverconnector-4.1.x-<ver>-updatesite.zip`

`{5x,413}/INSTALL.txt` are the templates for the dropin zips' INSTALL.txt
(`@VERSION@` and `@BUNDLE_VERSION@` are substituted at build time).

## Rebuild

The maintainer's build script automates everything below (plus the dropin
zips). The manual steps:

The publish is run inside the container whose Eclipse matches the
target KNIME version. Layout expected by the publisher:

```
<input>/
  plugins/
    org.ovf.serverconnector_<ver>.jar
  features/
    org.ovf.serverconnector.feature_<ver>.jar    # jar of feature.xml
```

Then:

```
LAUNCHER=$(ls /opt/knime/plugins/org.eclipse.equinox.launcher_*.jar)

# 1. Publish bundles + features into the repo
java -jar "$LAUNCHER" \
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
    -metadataRepository file:///out/repo \
    -artifactRepository file:///out/repo \
    -metadataRepositoryName "OVF Server Explorer" \
    -artifactRepositoryName "OVF Server Explorer" \
    -source /input \
    -publishArtifacts \
    -compress

# 2. Attach the category
java -jar "$LAUNCHER" \
    -application org.eclipse.equinox.p2.publisher.CategoryPublisher \
    -metadataRepository file:///out/repo \
    -categoryDefinition file:///path/to/category.xml \
    -compress
```

Zip the resulting `/out/repo/` (with `content.jar`, `artifacts.jar`,
`plugins/`, `features/` at the archive root -- NOT nested under a `repo/`
folder).

## Validate

Headless install into a scratch KNIME copy uses the same code path as
File > Install KNIME Extensions:

```
java -jar "$LAUNCHER" \
    -application org.eclipse.equinox.p2.director \
    -repository "jar:file:///path/to/updatesite.zip!/" \
    -installIU org.ovf.serverconnector.feature.feature.group \
    -destination /tmp/testinst \
    -profile KNIMEProfile
```
