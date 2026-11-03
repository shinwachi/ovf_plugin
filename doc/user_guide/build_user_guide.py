#!/usr/bin/env python3
"""Build the user guide from guide.src.html and img/.

Writes:
  ovf_user_guide.html  one file, images embedded, GIFs animated
  ovf_user_guide.pdf   GIFs replaced by their last frame (img/<name>_still.png)
  <plugin>/help/       the in-app copy for both plugin trees: user-guide.html,
                       img/, toc.xml (Help > Help Contents) and contexts.xml (F1)

Run it before tools/build-jars.sh, which packs help/ into the jars.
Needs google-chrome for the PDF.
"""
import base64, html, os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(HERE, "ovf_user_guide")
TREES = ["org.ovf.serverconnector", "org.ovf.serverconnector.413"]
PAGE = "help/user-guide.html"

# F1 context ids (OpenHelpAction.CONTEXT_*) -> sections to list.
CONTEXTS = {
    "view": ("Server Explorer",
             "Browse a shared workflow server or folder, and copy workflows between it and your workspace",
             ["parts", "connect", "open", "download", "upload", "trouble"]),
    "servers": ("Servers",
                "Add, change or remove the servers Server Explorer connects to, and choose the active one",
                ["connect", "trouble"]),
    "settings": ("Server Connector settings",
                 "Choose which messages Server Explorer shows, and see where the plugin is installed",
                 ["settings", "connect"]),
}

with open(os.path.join(HERE, "guide.src.html"), encoding="utf-8") as f:
    src = f.read()

sections = re.findall(r'<h2 id="(\w+)">(.*?)</h2>', src)
titles = {sid: html.unescape(re.sub(r"<.*?>", "", t)) for sid, t in sections}
images = sorted(set(re.findall(r'(?:src|data-still)="(img/[\w.-]+)"', src)))


def data_uri(path):
    ext = path.rsplit(".", 1)[1]
    with open(os.path.join(HERE, path), "rb") as f:
        return "data:image/%s;base64,%s" % (ext, base64.b64encode(f.read()).decode())


def stills(s):
    return re.sub(r'src="img/[\w.-]+\.gif" data-still="(img/[\w.-]+)"', r'src="\1"', s)


# Standalone page: everything embedded, dark mode allowed.
web = src.replace("<body>", '<body class="web">', 1)
web = re.sub(r' data-still="img/[\w.-]+"', "", web)
web = re.sub(r'src="(img/[\w.-]+)"', lambda m: 'src="%s"' % data_uri(m.group(1)), web)
with open(OUT + ".html", "w", encoding="utf-8") as f:
    f.write(web)

# PDF: still frames instead of animations.
with tempfile.TemporaryDirectory() as t:
    p = os.path.join(t, "print.html")
    pr = re.sub(r'src="(img/[\w.-]+)"', lambda m: 'src="%s"' % data_uri(m.group(1)), stills(src))
    with open(p, "w", encoding="utf-8") as f:
        f.write(pr)
    subprocess.run(["google-chrome", "--headless=new", "--disable-gpu", "--no-sandbox",
                    "--no-pdf-header-footer", "--user-data-dir=" + os.path.join(t, "u"),
                    "--print-to-pdf=" + OUT + ".pdf", "file://" + p],
                   check=True, stderr=subprocess.DEVNULL)

# In-app help: separate files, animated GIFs, no still frames.
helpimgs = sorted(set(re.findall(r'src="(img/[\w.-]+)"', src)))
toc = ['<?xml version="1.0" encoding="UTF-8"?>', '<?NLS TYPE="org.eclipse.help.toc"?>',
       '<toc label="OVF Server Explorer User Guide" topic="%s">' % PAGE]
toc += ['   <topic label="%s" href="%s#%s"/>' % (html.escape(titles[s], quote=True), PAGE, s)
        for s, _ in sections]
toc.append("</toc>")
ctx = ['<?xml version="1.0" encoding="UTF-8"?>', '<?NLS TYPE="org.eclipse.help.contexts"?>', "<contexts>"]
for cid, (title, desc, ids) in CONTEXTS.items():
    ctx.append('   <context id="%s" title="%s">' % (cid, html.escape(title, quote=True)))
    ctx.append("      <description>%s. The full guide is under Help &gt; Help Contents.</description>"
               % html.escape(desc))
    ctx += ['      <topic label="%s" href="%s#%s"/>' % (html.escape(titles[i], quote=True), PAGE, i)
            for i in ids]
    ctx.append("   </context>")
ctx.append("</contexts>")

for tree in TREES:
    h = os.path.join(ROOT, tree, "help")
    shutil.rmtree(h, ignore_errors=True)
    os.makedirs(os.path.join(h, "img"))
    with open(os.path.join(h, "user-guide.html"), "w", encoding="utf-8") as f:
        f.write(re.sub(r' data-still="img/[\w.-]+"', "", src))
    for i in helpimgs:
        shutil.copy(os.path.join(HERE, i), os.path.join(h, i))
    with open(os.path.join(h, "toc.xml"), "w", encoding="utf-8") as f:
        f.write("\n".join(toc) + "\n")
    with open(os.path.join(h, "contexts.xml"), "w", encoding="utf-8") as f:
        f.write("\n".join(ctx) + "\n")

missing = [i for i in images if not os.path.exists(os.path.join(HERE, i))]
if missing:
    sys.exit("missing images: " + " ".join(missing))
print("built", OUT + ".html", "and .pdf, and help/ in", " and ".join(TREES))
