#!/usr/bin/env python3
"""Remove the build machine's home directory from a linked library.

    scrub-build-paths.py <library> <home-dir>

Compilers record source paths in the binary: __FILE__ in assertion messages
(QEMU's QOM casts, glib, pcre2), Rust panic locations (the DSP56300 crate's
~/.cargo sources) and glib's compiled-in install directories.  On a build
machine those paths start with the builder's home directory, which names them.

Each occurrence of <home-dir> is replaced by a neutral path of the same
length ("/build" padded with slashes, still a valid path), so no offset,
size or string length in the file changes.  The build then fails if the
user name still appears anywhere, rather than shipping it.

Run by CMakeLists.txt after linking libxemu.so, before Gradle strips it.
"""
import os
import sys


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    path, home = sys.argv[1], sys.argv[2].rstrip("/")
    user = os.path.basename(home)
    if len(home) < len("/build") or not user:
        sys.exit(f"scrub-build-paths: unusable home directory {home!r}")

    old = home.encode()
    new = (b"/build" + b"/" * len(old))[:len(old)]
    with open(path, "rb") as f:
        data = f.read()
    count = data.count(old)
    if count:
        data = data.replace(old, new)
        with open(path, "wb") as f:
            f.write(data)

    left = data.count(user.encode())
    if left:
        sys.exit(f"scrub-build-paths: {left} occurrence(s) of {user!r} remain "
                 f"in {path} outside {home}")
    print(f"scrub-build-paths: {path}: {count} build path(s) neutralised")


if __name__ == "__main__":
    main()
