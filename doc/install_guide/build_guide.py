#!/usr/bin/env python3
"""Build ovf_install_guide.html and .pdf from guide.src.html and diagrams/*.dot.

Diagrams and images are embedded, so the HTML works offline from the installer
zip. Set DOT if graphviz's dot isn't on PATH.
"""
import base64, os, re, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
DOT = os.environ.get("DOT", "dot")
OUT = os.path.join(HERE, "ovf_install_guide")


def svg(m):
    p = os.path.join(HERE, "diagrams", m.group(1) + ".dot")
    s = subprocess.check_output([DOT, "-Tsvg", p]).decode()
    s = s[s.index("<svg"):]
    s = re.sub(r'<title>.*?</title>', "", s)
    s = re.sub(r'<svg width="(\d+)pt" height="(\d+)pt"',
               r'<svg role="img" aria-label="%s" style="width:\1pt;max-width:100%%;height:auto"' % m.group(2), s)
    return s


def img(m):
    with open(os.path.join(HERE, m.group(1)), "rb") as f:
        return "data:image/png;base64," + base64.b64encode(f.read()).decode()


with open(os.path.join(HERE, "guide.src.html"), encoding="utf-8") as f:
    html = f.read()
html = re.sub(r'<!--svg:(\w+) (.*?)-->', svg, html)
html = re.sub(r'@img:([\w./-]+)@', img, html)
with open(OUT + ".html", "w", encoding="utf-8") as f:
    f.write(html)

with tempfile.TemporaryDirectory() as t:
    subprocess.run(["google-chrome", "--headless=new", "--disable-gpu", "--no-sandbox",
                    "--no-pdf-header-footer", "--user-data-dir=" + t,
                    "--print-to-pdf=" + OUT + ".pdf", "file://" + OUT + ".html"],
                   check=True, stderr=subprocess.DEVNULL)
print("built", OUT + ".html", "and .pdf")
sys.exit(0)
