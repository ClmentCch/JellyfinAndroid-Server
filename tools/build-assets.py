#!/usr/bin/env python3
"""
Build the runtime bundle embedded in the JellyfinServer Android app.

The Android APK ships a tiny Linux userland (a pruned Debian rootfs) that
contains:

  * the official Jellyfin server build for linux/arm64 (12.3.x, extracted from
    the official jellyfin/jellyfin container image),
  * jellyfin-ffmpeg + ffprobe (used for transcoding),
  * proot + its ELF loader + libtalloc (Termux build), which lets the app run
    the Linux binaries without root,
  * the few Debian shared libraries the server needs.

Outputs (all git-ignored, the build regenerates them on demand):

  app/src/main/assets/rootfs.bin      pruned rootfs (gzip compressed tar)
  app/src/main/assets/rootfs.sha256   checksum, verified at install time
  app/src/main/assets/proot/proot     proot binary (aarch64)
  app/src/main/assets/proot/loader    proot ELF loader (aarch64)
  app/src/main/assets/proot/loader32  proot ELF loader (arm32)
  app/src/main/assets/proot/libtalloc.so.2
  app/src/main/assets/proot/libandroid-shmem.so

The script only uses the Python standard library: it never needs `ar`, `tar`,
`cp`, administrator rights or the ability to create symlinks. The rootfs is
rebuilt by copying the container image layers straight into the output archive,
so it works identically on Windows, macOS and Linux.

Usage:
    python tools/build-assets.py [--force]
"""

import argparse
import fnmatch
import gzip
import hashlib
import io
import json
import os
import posixpath
import shutil
import sys
import tarfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, ".cache-assets")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

IMAGE = "jellyfin/jellyfin"
IMAGE_TAG = os.environ.get("JELLYFIN_IMAGE_TAG", "latest")
IMAGE_ARCH = "arm64"

TERMUX_REPO = "https://packages.termux.dev/apt/termux-main/pool/main"
PROOT_DEB = TERMUX_REPO + "/p/proot/proot_5.1.107.100_aarch64.deb"
TALLOC_DEB = TERMUX_REPO + "/libt/libtalloc/libtalloc_2.5.0_aarch64.deb"
SHMEM_DEB = TERMUX_REPO + "/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb"

# Paths removed from the image. Everything here is either documentation, apt
# bookkeeping, desktop-only fonts, or GPU drivers that cannot be used from an
# unprivileged Android process (the Mali blob and the AMD/RADV stack).
PRUNE = [
    "usr/share/fonts/opentype",
    "usr/share/fonts/truetype/arphic",
    "usr/share/fonts/truetype/unfonts-core",
    "usr/share/fonts/truetype/wqy",
    "usr/share/i18n",
    "usr/share/doc",
    "usr/share/man",
    "usr/share/info",
    "usr/share/perl5",
    "usr/lib/apt",
    "usr/lib/jellyfin-ffmpeg/lib/libvulkan_radeon.so",
    "usr/lib/jellyfin-ffmpeg/lib/dri",
    "var/cache/apt",
    "var/lib/apt",
    "var/log",
    "var/lib/systemd",
]

# Globs removed as well (fonts we do not need, GPU blobs, perl helpers).
PRUNE_GLOBS = [
    "usr/share/fonts/truetype/fonts-japanese-*",
    "usr/lib/aarch64-linux-gnu/libmali.so*",
    "usr/bin/perl*",
]

# Files rewritten by the builder. They are skipped while copying the image so
# that a possible symlink (e.g. systemd-resolved's stub resolv.conf) is never
# extracted as a regular file.
OVERRIDDEN = ("etc/resolv.conf", "etc/hosts")

RESOLV_CONF = b"""nameserver 1.1.1.1
nameserver 8.8.8.8
nameserver 9.9.9.9
"""

HOSTS = b"""127.0.0.1 localhost
::1 localhost ip6-localhost ip6-loopback
"""

# Directories created (or forced) at the root of the guest filesystem.
RUNTIME_DIRS = (
    ("tmp", 0o1777),
    ("root", 0o0700),
    ("config", 0o0755),
    ("cache", 0o0755),
    ("media", 0o0755),
)

CHUNK = 1024 * 1024


def log(message):
    print("[assets] " + message, flush=True)


def normalize(name):
    """Turn an archive member name into a clean, relative POSIX path."""
    name = name.replace("\\", "/")
    while name.startswith("./"):
        name = name[2:]
    if not name or name == ".":
        return None
    name = posixpath.normpath(name)
    if name == ".." or name.startswith("../"):
        return None
    name = name.lstrip("/")
    return name or None


def is_pruned(name):
    for prefix in PRUNE:
        if name == prefix or name.startswith(prefix + "/"):
            return True
    directory = posixpath.dirname(name)
    base = posixpath.basename(name)
    for pattern in PRUNE_GLOBS:
        if posixpath.dirname(pattern) == directory and fnmatch.fnmatchcase(
            base, posixpath.basename(pattern)
        ):
            return True
    return False


# --------------------------------------------------------------------------
# HTTP helpers
# --------------------------------------------------------------------------


def _request(url, headers=None, accept=None, timeout=300):
    request = urllib.request.Request(url, headers=dict(headers or {}))
    request.add_header("User-Agent", "JellyfinServer-assets/1.0")
    if accept:
        request.add_header("Accept", accept)
    return urllib.request.urlopen(request, timeout=timeout)


def download(url, destination, headers=None, accept=None):
    if os.path.isfile(destination) and os.path.getsize(destination) > 0:
        log("cached %s" % os.path.basename(destination))
        return destination
    log("downloading %s" % url)
    os.makedirs(os.path.dirname(destination), exist_ok=True)
    tmp = destination + ".part"
    with _request(url, headers, accept, timeout=600) as response:
        with open(tmp, "wb") as out:
            shutil.copyfileobj(response, out, CHUNK)
    os.replace(tmp, destination)
    return destination


# --------------------------------------------------------------------------
# Docker image layers
# --------------------------------------------------------------------------


def pull_layers():
    """Download the arm64 image layers and return their local paths, in order."""
    index_file = os.path.join(CACHE, "layers.json")
    layers_dir = os.path.join(CACHE, "layers")

    if os.path.isfile(index_file) and not args.force:
        try:
            with open(index_file) as handle:
                paths = json.load(handle)
            if paths and all(os.path.isfile(path) for path in paths):
                log("cached %d image layers" % len(paths))
                return paths
        except (ValueError, OSError):
            pass

    os.makedirs(layers_dir, exist_ok=True)
    log("resolving %s:%s (%s)" % (IMAGE, IMAGE_TAG, IMAGE_ARCH))

    token = json.load(
        _request(
            "https://auth.docker.io/token?service=registry.docker.io"
            "&scope=repository:%s:pull" % IMAGE
        )
    )["token"]
    headers = {"Authorization": "Bearer " + token}

    index = json.load(
        _request(
            "https://registry-1.docker.io/v2/%s/manifests/%s" % (IMAGE, IMAGE_TAG),
            headers,
            "application/vnd.oci.image.index.v1+json, "
            "application/vnd.docker.distribution.manifest.list.v2+json",
        )
    )
    digests = [
        manifest["digest"]
        for manifest in index.get("manifests", [])
        if manifest["platform"]["os"] == "linux"
        and manifest["platform"]["architecture"] == IMAGE_ARCH
    ]
    if not digests:
        raise SystemExit("No linux/%s manifest for %s:%s" % (IMAGE_ARCH, IMAGE, IMAGE_TAG))

    manifest = json.load(
        _request(
            "https://registry-1.docker.io/v2/%s/manifests/%s" % (IMAGE, digests[0]),
            headers,
            "application/vnd.oci.image.manifest.v1+json, "
            "application/vnd.docker.distribution.manifest.v2+json",
        )
    )

    paths = []
    total = len(manifest["layers"])
    for number, layer in enumerate(manifest["layers"]):
        digest = layer["digest"].split(":", 1)[-1]
        destination = os.path.join(layers_dir, digest + ".layer")
        if os.path.isfile(destination) and os.path.getsize(destination) == layer["size"]:
            log("cached layer %d/%d" % (number + 1, total))
        else:
            log(
                "layer %d/%d (%.1f MiB)"
                % (number + 1, total, layer["size"] / 1048576.0)
            )
            download(
                "https://registry-1.docker.io/v2/%s/blobs/%s" % (IMAGE, layer["digest"]),
                destination,
                headers,
            )
        paths.append(destination)

    with open(index_file, "w") as handle:
        json.dump(paths, handle)
    return paths


def open_layer(path):
    try:
        return tarfile.open(path, "r:*")
    except tarfile.ReadError as error:
        raise SystemExit(
            "Cannot read the image layer %s (%s).\n"
            "The image probably uses zstd compression; rebuild it with a Python "
            "that supports it (3.14+) or set JELLYFIN_IMAGE_TAG to a release "
            "that uses gzip layers." % (os.path.basename(path), error)
        )


def resolve_layers(layers):
    """Apply the Docker layer overlay and return {path: layer index}."""
    entries = {}
    for index, path in enumerate(layers):
        with open_layer(path) as tar:
            for member in tar:
                name = normalize(member.name)
                if name is None:
                    continue
                base = posixpath.basename(name)
                parent = posixpath.dirname(name)
                if base == ".wh..wh..opq":
                    prefix = parent + "/" if parent else ""
                    for key in [k for k in entries if k.startswith(prefix)]:
                        del entries[key]
                    continue
                if base.startswith(".wh."):
                    target = posixpath.join(parent, base[4:])
                    prefix = target + "/"
                    for key in [k for k in entries if k == target or k.startswith(prefix)]:
                        del entries[key]
                    continue
                entries[name] = index
    return entries


# --------------------------------------------------------------------------
# Tar writing
# --------------------------------------------------------------------------


def make_member(member, name):
    info = tarfile.TarInfo(name)
    info.type = member.type
    info.mode = member.mode
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 0
    info.size = member.size if member.isfile() else 0
    if member.issym():
        info.linkname = member.linkname
    return info


def make_dir(name, mode):
    info = tarfile.TarInfo(name)
    info.type = tarfile.DIRTYPE
    info.mode = mode
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 0
    return info


def make_file(name, mode, payload):
    info = tarfile.TarInfo(name)
    info.type = tarfile.REGTYPE
    info.mode = mode
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    info.mtime = 0
    info.size = len(payload)
    return info, io.BytesIO(payload)


def pack_rootfs(layers):
    """Write app/src/main/assets/rootfs.bin from the image layers."""
    os.makedirs(ASSETS, exist_ok=True)
    archive = os.path.join(ASSETS, "rootfs.bin")
    partial = archive + ".part"

    log("merging %d layers into %s" % (len(layers), os.path.basename(archive)))
    entries = resolve_layers(layers)
    for required in ("jellyfin/jellyfin", "usr/lib/jellyfin-ffmpeg/ffmpeg"):
        if required not in entries:
            raise SystemExit("Missing %s in the image" % required)
    if not any(key.startswith("jellyfin/") for key in entries):
        raise SystemExit("The image does not look like a Jellyfin server build")

    written = 0
    hardlinks = []
    try:
        with open(partial, "wb") as raw:
            with gzip.GzipFile(
                filename="rootfs", mode="wb", fileobj=raw, compresslevel=6, mtime=0
            ) as gz:
                with tarfile.open(fileobj=gz, mode="w", format=tarfile.GNU_FORMAT) as out:
                    for index, layer in enumerate(layers):
                        with open_layer(layer) as tar:
                            for member in tar:
                                name = normalize(member.name)
                                if name is None or entries.get(name) != index:
                                    continue
                                if name in OVERRIDDEN or is_pruned(name):
                                    continue
                                info = make_member(member, name)
                                if member.islnk():
                                    hardlinks.append((info, normalize(member.linkname)))
                                    continue
                                data = tar.extractfile(member) if member.isfile() else None
                                out.addfile(info, data)
                                written += 1

                    # Hard links are rewritten as full copies: a hard link may
                    # point to a file that the overlay removed.
                    for info, target in hardlinks:
                        source = entries.get(target)
                        if source is None:
                            continue
                        with open_layer(layers[source]) as tar:
                            member = tar.getmember(target)
                            data = tar.extractfile(member)
                            info.size = member.size
                            out.addfile(info, data)
                            written += 1

                    for name, mode in RUNTIME_DIRS:
                        out.addfile(make_dir(name, mode))
                    for name, payload in (
                        ("etc/resolv.conf", RESOLV_CONF),
                        ("etc/hosts", HOSTS),
                    ):
                        info, data = make_file(name, 0o644, payload)
                        out.addfile(info, data)
                    # The Debian image ships /etc/hosts and /etc/resolv.conf as
                    # symlinks on some releases; make sure they are regular
                    # files before the server starts.
        os.replace(partial, archive)
    except BaseException:
        if os.path.isfile(partial):
            os.remove(partial)
        raise

    digest = hashlib.sha256()
    with open(archive, "rb") as handle:
        for chunk in iter(lambda: handle.read(CHUNK), b""):
            digest.update(chunk)
    checksum = digest.hexdigest()
    with open(checksum_file(), "w") as handle:
        handle.write(checksum + "\n")
    log(
        "rootfs %d entries, %.1f MiB, sha256 %s"
        % (written, os.path.getsize(archive) / 1048576.0, checksum)
    )


def checksum_file():
    return os.path.join(ASSETS, "rootfs.sha256")


# --------------------------------------------------------------------------
# proot (Termux packages)
# --------------------------------------------------------------------------


def ar_data(deb_path):
    """"Return a file object for the data.tar member of a .deb (no `ar` needed)."""
    with open(deb_path, "rb") as handle:
        if handle.read(8) != b"!<arch>\n":
            raise SystemExit("%s is not a Debian package" % deb_path)
        while True:
            header = handle.read(60)
            if len(header) < 60:
                raise SystemExit("No data.tar member in %s" % deb_path)
            name = header[0:16].decode("ascii", "replace").strip()
            try:
                size = int(header[48:58].decode("ascii").strip())
            except ValueError:
                raise SystemExit("Corrupt ar header in %s" % deb_path)
            if name.startswith("data.tar"):
                return io.BytesIO(handle.read(size))
            handle.seek(size + (size & 1), os.SEEK_CUR)


def deb_extract(deb_path, wanted):
    """Extract the members of `wanted` ({archive name: destination}) from a .deb."""
    found = 0
    stream = ar_data(deb_path)
    with tarfile.open(fileobj=stream, mode="r:*") as tar:
        for member in tar:
            name = normalize(member.name)
            destination = wanted.get(name)
            if destination is None or not member.isfile():
                continue
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            with open(destination, "wb") as out:
                shutil.copyfileobj(tar.extractfile(member), out, CHUNK)
            os.chmod(destination, 0o755)
            found += 1
    if found != len(wanted):
        raise SystemExit(
            "Missing files in %s (found %d/%d)" % (deb_path, found, len(wanted))
        )


def build_proot_assets():
    proot_dir = os.path.join(ASSETS, "proot")
    if os.path.isdir(proot_dir) and not args.force:
        log("cached proot assets")
        return
    os.makedirs(CACHE, exist_ok=True)
    os.makedirs(proot_dir, exist_ok=True)

    proot_deb = download(PROOT_DEB, os.path.join(CACHE, "proot.deb"))
    talloc_deb = download(TALLOC_DEB, os.path.join(CACHE, "libtalloc.deb"))
    shmem_deb = download(SHMEM_DEB, os.path.join(CACHE, "libandroid-shmem.deb"))

    termux = "data/data/com.termux/files/usr/"
    deb_extract(
        proot_deb,
        {
            termux + "bin/proot": os.path.join(proot_dir, "proot"),
            termux + "libexec/proot/loader": os.path.join(proot_dir, "loader"),
            termux + "libexec/proot/loader32": os.path.join(proot_dir, "loader32"),
        },
    )
    deb_extract(
        talloc_deb,
        {termux + "lib/libtalloc.so.2.5.0": os.path.join(proot_dir, "libtalloc.so.2")},
    )
    # proot links against Termux's libandroid-shmem for shared memory support.
    deb_extract(
        shmem_deb,
        {termux + "lib/libandroid-shmem.so": os.path.join(proot_dir, "libandroid-shmem.so")},
    )

    with open(os.path.join(proot_dir, "VERSION"), "w") as handle:
        handle.write(
            "proot 5.1.107.100 (Termux), libtalloc 2.5.0, libandroid-shmem 0.7\n"
        )
    log("proot assets written to %s" % proot_dir)


def main():
    global args
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="rebuild everything")
    args = parser.parse_args()

    os.makedirs(CACHE, exist_ok=True)

    archive = os.path.join(ASSETS, "rootfs.bin")
    if os.path.isfile(archive) and not args.force:
        log("rootfs asset already present, nothing to do (use --force to rebuild)")
    else:
        pack_rootfs(pull_layers())

    build_proot_assets()

    total = 0
    for directory, _, files in os.walk(ASSETS):
        for name in files:
            total += os.path.getsize(os.path.join(directory, name))
    log("asset bundle ready: %.1f MiB in %s" % (total / 1048576.0, ASSETS))


if __name__ == "__main__":
    if sys.version_info < (3, 8):
        raise SystemExit("Python 3.8 or newer is required")
    main()
