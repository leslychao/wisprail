"""Add owned service cleanup to jpackage's own WiX template, without replacing its upgrade logic."""
import argparse
from pathlib import Path
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with zipfile.ZipFile(args.jdk / "jmods" / "jdk.jpackage.jmod") as archive:
        template = archive.read("classes/jdk/jpackage/internal/resources/main.wxs").decode("utf-8")
    marker = '<UIRef Id="JpUI"/>'
    sequence = '<InstallExecuteSequence>'
    if template.count(marker) != 1 or template.count(sequence) != 1:
        raise SystemExit("Unsupported jpackage WiX template; review service lifecycle integration")
    action = '''<CustomAction Id="WisprailCleanup" Directory="INSTALLDIR"
      ExeCommand="&quot;[SystemFolder]WindowsPowerShell\\v1.0\\powershell.exe&quot; -NoProfile -NonInteractive -File &quot;[INSTALLDIR]app\\uninstall-service.ps1&quot;"
      Execute="deferred" Impersonate="no" Return="check"/>'''
    template = template.replace(marker, marker + "\n" + action)
    template = template.replace(sequence, sequence + '\n<Custom Action="WisprailCleanup" Before="RemoveFiles">Installed AND REMOVE="ALL"</Custom>')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "main.wxs").write_text(template, encoding="utf-8")


if __name__ == "__main__":
    main()
