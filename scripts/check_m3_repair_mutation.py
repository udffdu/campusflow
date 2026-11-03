"""Verify self-acceptance test detects a real authorization defect; always restore source."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
service = root / "services/maintenance"
source = service / "src/main/java/io/campusflow/maintenance/Lifecycle.java"
original = source.read_bytes()
needle = b'if(action.acceptance&&actor.equals(order.get("assigneeId")))throw new Failure(403,"SELF_ACCEPTANCE_FORBIDDEN");'
if original.count(needle) != 1:
    raise SystemExit("Mutation target changed; inspect before running")
log = service / "target/repair-mutation.log"
log.parent.mkdir(exist_ok=True)
try:
    source.write_bytes(original.replace(needle, b'// MUTATION: self-acceptance guard removed'))
    import os
    command = (["cmd", "/c", "mvnw.cmd"] if os.name == "nt" else ["./mvnw"])
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(command + ["-q", "-Dtest=ReportingTest#lifecycleSelfAcceptanceAndDefaultGate", "test"], cwd=service, stdout=output, stderr=subprocess.STDOUT)
    report = ET.parse(service / "target/surefire-reports/TEST-io.campusflow.maintenance.ReportingTest.xml").getroot()
    failures = report.findall(".//failure")
    if result.returncode == 0 or len(failures) != 1 or "403" not in failures[0].attrib.get("message", "") or "200" not in failures[0].attrib.get("message", ""):
        raise SystemExit("Mutation was not detected as the expected forbidden-versus-success mismatch; inspect " + str(log))
    print("PASS: removing self-acceptance guard causes expected 403/200 test failure")
finally:
    source.write_bytes(original)
    print("Source restored; run full verify to restore normal build artifacts")
