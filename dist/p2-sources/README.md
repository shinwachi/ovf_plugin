# p2 archived-update-site sources

These `feature.xml` + `category.xml` files are the inputs that produce the
Eclipse p2 update-site archives in `dist/`:

- `knime-serverconnector-5.x-1.0.0-updatesite.zip`
- `knime-serverconnector-4.1.x-1.0.0-updatesite.zip`

## Rebuild

The publish is run inside the container whose Eclipse matches the
target KNIME version. Layout expected by the publisher:

```
<input>/
  plugins/
    org.knime.serverconnector_<ver>.jar
  features/
    org.knime.serverconnector.feature_<ver>.jar    # jar of feature.xml
```

Then:

```
LAUNCHER=$(ls /opt/knime/plugins/org.eclipse.equinox.launcher_*.jar)

# 1. Publish bundles + features into the repo
java -jar "$LAUNCHER" \
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
    -metadataRepository file:///out/repo \
    -artifactRepository file:///out/repo \
    -metadataRepositoryName "KNIME Server Connector" \
    -artifactRepositoryName "KNIME Server Connector" \
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
    -installIU org.knime.serverconnector.feature.feature.group \
    -destination /tmp/testinst \
    -profile KNIMEProfile
```
