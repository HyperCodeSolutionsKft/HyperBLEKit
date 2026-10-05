#!/usr/bin/env python3
"""Fails if a public symbol of the HyperBleKit library has no KDoc.

The library is built with Kotlin's explicit API mode, so every public declaration carries the
word `public` — except enum entries and the constructor properties of a public class, which this
script finds by their position. A symbol counts as documented when a KDoc block ends right above
it (annotations may stand in between), or, for a constructor parameter or property, when the
class's KDoc has a `@param` / `@property` tag for it.

Usage: python3 tools/check_kdoc.py [source root]   (default: hyperblekit/src/main/java)
"""
import pathlib
import re
import sys

DECLARATION = re.compile(
    r"^\s*public\s+(?:(?:data|sealed|enum|abstract|open|const|override|inline|value|fun|companion|lateinit)\s+)*"
    r"(class|interface|object|fun|val|var|constructor|typealias)\b\s*(?:<[^>]*>\s*)?(?:[\w.]+\.)?(`[^`]+`|\w+)?"
)
PUBLIC_TYPE = re.compile(r"^\s*public\s+(?:(?:data|sealed|enum|abstract|open|value)\s+)*(class|interface|object)\s+(\w+)")
CONSTRUCTOR_PROPERTY = re.compile(r"^\s+(?:public\s+)?(?:val|var)\s+(\w+)\s*:")
ENUM_ENTRY = re.compile(r"^\s+([A-Z][A-Z0-9_]*)\s*(?:\(.*\))?\s*[,;]?\s*$")


def kdoc_above(lines, index):
    """The KDoc block that ends right above line `index`, or None."""
    i = index - 1
    while i >= 0 and (lines[i].strip().startswith("@") or not lines[i].strip()):
        if not lines[i].strip():
            return None
        i -= 1
    if i < 0 or not lines[i].rstrip().endswith("*/"):
        return None
    end = i
    while i >= 0 and "/**" not in lines[i]:
        i -= 1
    return "\n".join(lines[i:end + 1]) if i >= 0 else None


def check(path):
    problems = []
    lines = path.read_text(encoding="utf-8").split("\n")
    for index, line in enumerate(lines):
        declaration = DECLARATION.match(line)
        if declaration and kdoc_above(lines, index) is None:
            # A property in a constructor may be documented by the class's @property / @param.
            name = declaration.group(2) or declaration.group(1)
            owner = enclosing_constructor(lines, index)
            if owner is None or not tagged(kdoc_above(lines, owner), name):
                problems.append((index + 1, line.strip()))
        public_type = PUBLIC_TYPE.match(line)
        if not public_type:
            continue
        type_doc = kdoc_above(lines, index) or ""
        if "enum" in line.split("class")[0]:
            body = index + 1
            while body < len(lines) and not lines[body].startswith("}"):
                entry = ENUM_ENTRY.match(lines[body])
                if entry and kdoc_above(lines, body) is None:
                    problems.append((body + 1, "enum entry " + entry.group(1)))
                if lines[body].strip().endswith(";"):
                    break
                body += 1
        elif line.rstrip().endswith("(") or ("(" in line and ")" in line):
            for number, name in constructor_properties(lines, index):
                # On the class's own line only a tag documents it; on its own line a KDoc above does too.
                own_doc = kdoc_above(lines, number) if number != index else None
                if not tagged(type_doc, name) and own_doc is None:
                    problems.append((number + 1, "constructor property " + name))
    return problems


def tagged(doc, name):
    return doc is not None and re.search(r"@(?:property|param)\s+" + re.escape(name) + r"\b", doc) is not None


def enclosing_constructor(lines, index):
    """The line of the class whose primary constructor contains line `index`, or None."""
    i = index - 1
    while i >= 0:
        stripped = lines[i].strip()
        if stripped.startswith(")") or stripped.endswith("{") or stripped == "}":
            return None
        if PUBLIC_TYPE.match(lines[i]) and lines[i].rstrip().endswith("("):
            return i
        i -= 1
    return None


def constructor_properties(lines, index):
    """(line, name) of every val/var in the primary constructor that starts on line `index`."""
    found = []
    line = lines[index]
    if line.rstrip().endswith("("):
        i = index + 1
        while i < len(lines) and not lines[i].strip().startswith(")"):
            match = CONSTRUCTOR_PROPERTY.match(lines[i])
            if match and "private " not in lines[i] and "internal " not in lines[i]:
                found.append((i, match.group(1)))
            i += 1
    else:
        inside = line[line.index("(") + 1:line.rindex(")")]
        for match in re.finditer(r"(private\s+|internal\s+)?(?<!\w)(?:val|var)\s+(\w+)\s*:", inside):
            if match.group(1) is None:
                found.append((index, match.group(2)))
    return found


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "hyperblekit/src/main/java")
    failures = 0
    symbols = 0
    for path in sorted(root.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        symbols += len(re.findall(r"^\s*public\s", text, flags=re.M))
        for number, what in check(path):
            failures += 1
            print(f"{path}:{number}: no KDoc: {what}")
    print(f"{symbols} public declarations checked, {failures} without KDoc")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
