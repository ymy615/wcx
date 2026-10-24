"""Check the actual APK's retained feature classes and Xposed entry points."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile


def dex_classes(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Unsupported DEX header")
    u32 = lambda offset: struct.unpack_from("<I", data, offset)[0]
    string_count, string_offset = u32(56), u32(60)
    type_count, type_offset = u32(64), u32(68)
    class_count, class_offset = u32(96), u32(100)

    def string(index):
        if index >= string_count:
            raise ValueError("Invalid DEX string index")
        pos = u32(string_offset + 4 * index)
        for _ in range(5):
            value = data[pos]
            pos += 1
            if value < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.index(0, pos)
        # Class descriptors and package names are ASCII; other DEX strings are not read.
        return data[pos:end].decode("ascii")

    result = set()
    for index in range(class_count):
        type_index = u32(class_offset + index * 32)
        if type_index >= type_count:
            raise ValueError("Invalid DEX class type")
        result.add(string(u32(type_offset + type_index * 4)))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--registry", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    registry = args.registry.read_text(encoding="utf-8", errors="replace")
    features = set(re.findall(r"// Field (com/Johnny/wcx/features/[^ .:]+)\.INSTANCE:", registry))
    if len(features) < 300:
        raise ValueError(f"Unexpected feature registry size: {len(features)}")
    defined = set()
    entries = {}
    with zipfile.ZipFile(args.apk) as archive:
        dex_names = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
        for name in dex_names:
            defined.update(dex_classes(archive.read(name)))
        for name in ("assets/xposed_init", "META-INF/xposed/java_init.list"):
            entries[name] = [line.strip() for line in archive.read(name).decode().splitlines()
                             if line.strip() and not line.lstrip().startswith("#")]
        required = {"L" + name + ";" for name in features}
        required.update("L" + name.replace(".", "/") + ";"
                        for values in entries.values() for name in values)
        missing = sorted(required - defined)
        if missing:
            raise ValueError("Missing retained classes: " + ", ".join(missing))
        if any("/items/secret_friend/" in name for name in defined):
            raise ValueError("Parallel legacy secret-friend engine found")
        libraries = {name: hashlib.sha256(archive.read(name)).hexdigest()
                     for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")}
    result = {
        "apk": str(args.apk.resolve()),
        "sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
        "sizeBytes": args.apk.stat().st_size,
        "dexFiles": len(dex_names),
        "definedClasses": len(defined),
        "registeredFeatureClassesVerified": len(features),
        "requiredClassesVerified": len(required),
        "entryPoints": entries,
        "nativeLibraries": libraries,
        "legacyHidingEnginePresent": False,
        "scope": "Static APK class and entry-point presence only; runtime behavior is untested.",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("registeredFeatureClassesVerified", "requiredClassesVerified", "dexFiles", "sizeBytes", "sha256")}))


if __name__ == "__main__":
    main()
