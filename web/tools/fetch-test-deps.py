"""Fetch JVM-only test dependencies into web/build (no Android SDK, no project dependency changes)."""
from pathlib import Path
from urllib.request import urlopen
import argparse

DEPENDENCIES = (
    ("com.squareup.okhttp3", "okhttp-jvm", "5.5.1"),
    ("com.squareup.okio", "okio-jvm", "3.18.1"),
    ("org.json", "json", "20240303"),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path)
    parser.add_argument("--shared", action="store_true", help="Fetch the shared core/AI/app JVM dependencies.")
    args = parser.parse_args()
    if args.destination is None:
        args.destination = (Path(__file__).resolve().parents[3] / ".tools" / "olfact" / "lib"
                            if args.shared else Path(__file__).resolve().parents[1] / "build" / "pure-jvm-deps")
    dependencies = DEPENDENCIES if not args.shared else (
        DEPENDENCIES[0], DEPENDENCIES[1],
        ("org.json", "json", "20250107"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.11.0"),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.11.0"),
    )
    args.destination.mkdir(parents=True, exist_ok=True)
    for group, artifact, version in dependencies:
        name = f"{artifact}-{version}.jar"
        target = args.destination / name
        if target.exists():
            print(f"Reusing {name}")
            continue
        url = f"https://repo.maven.apache.org/maven2/{group.replace('.', '/')}/{artifact}/{version}/{name}"
        with urlopen(url, timeout=30) as response:
            payload = response.read()
        target.write_bytes(payload)
        print(f"Downloaded {name}: {len(payload)} bytes")


if __name__ == "__main__":
    main()
