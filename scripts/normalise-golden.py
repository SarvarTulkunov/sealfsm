"""Make a golden capture comparable to another capture of the same code.

Two things in `summary.txt` vary with WHERE the capture was written rather than
with what the analysis found, and both would otherwise mask a real diff behind
noise on every fixture:

  * the absolute output directory, printed in the "wrote N file(s) to ..." line;
  * SLF4J's "no providers were found" notice, which is emitted to stderr and so
    interleaves with stdout non-deterministically.

Everything else is left exactly as printed — the counts, the commit/successor
axes and the diagnostics are the point of capturing the summary at all.
"""
import os
import sys

dest = sys.argv[1]
dest_forms = {os.path.abspath(dest), os.path.abspath(dest).replace("\\", "/")}

for name in sorted(os.listdir(dest)):
    path = os.path.join(dest, name, "summary.txt")
    if not os.path.isfile(path):
        continue
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.readlines()
    out = []
    for line in lines:
        if line.startswith("SLF4J"):
            continue
        for form in dest_forms:
            line = line.replace(os.path.join(form, name), "<out>")
            line = line.replace(os.path.join(form, name).replace("\\", "/"), "<out>")
            line = line.replace(form, "<out-root>")
        out.append(line)
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.writelines(out)
