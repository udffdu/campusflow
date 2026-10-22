#!/usr/bin/env python3
"""Validate the bootstrap layout and requirement register, not application behavior."""

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main():
    errors = []

    def require(condition, message):
        if not condition:
            errors.append(message)

    required = [
        "README.md", "AGENTS.md", "CONTRIBUTING.md", ".gitignore",
        "apps/web/README.md", "contracts/README.md", "infra/README.md",
        "docs/architecture/module-boundaries.md",
        "docs/architecture/adr-0001-repository-and-services.md",
        "docs/requirements/m2.md", "docs/requirements/risks-and-ethics.md",
        "docs/process/github-setup.md", "docs/process/bootstrap-review.md",
        ".github/pull_request_template.md", ".github/workflows/repository-checks.yml",
    ]
    for name in required:
        require((ROOT / name).is_file(), f"Missing required file: {name}")

    def read_json(name):
        try:
            return json.loads((ROOT / name).read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            errors.append(f"Cannot read {name}: {exc}")
            return {}

    def check_path(value, context):
        if not isinstance(value, str):
            errors.append(f"{context}: path must be a string")
            return
        candidate = (ROOT / value).resolve()
        require(candidate.is_relative_to(ROOT), f"{context}: path escapes repository")
        require(candidate.exists(), f"{context}: missing {value}")

    project = read_json("spec/project.json")
    require(isinstance(project, dict), "project.json must contain an object")
    if not isinstance(project, dict):
        project = {}
    modules = project.get("modules", [])
    require(isinstance(modules, list), "modules must be a list")
    if not isinstance(modules, list):
        modules = []
    module_ids = []
    for module in modules:
        if not isinstance(module, dict):
            errors.append("Each module must be an object")
            continue
        module_ids.append(module.get("id"))
        check_path(module.get("path"), "module")
    require(len(module_ids) == 4 and set(module_ids) == {"D", "M1", "M2", "M3"},
            "Module register must contain D, M1, M2 and M3 exactly once")

    register = read_json("spec/requirements.json")
    if not isinstance(register, dict):
        errors.append("requirements.json must contain an object")
        register = {}
    rows = register.get("requirements", [])
    require(isinstance(rows, list) and bool(rows), "Requirement register must be non-empty")
    if not isinstance(rows, list):
        rows = []
    seen = set()
    states = {"planned", "in_progress", "implemented", "verified", "deferred"}
    for row in rows:
        if not isinstance(row, dict):
            errors.append("Each requirement must be an object")
            continue
        rid = row.get("id", "")
        if not isinstance(rid, str):
            errors.append("Requirement id must be a string")
            continue
        require(bool(re.fullmatch(r"CF-(M[123]|D|INT|ENG)-[0-9]{3}", rid)), f"Invalid id: {rid}")
        require(rid not in seen, f"Duplicate requirement: {rid}")
        seen.add(rid)
        module = row.get("module")
        require(module in {"D", "M1", "M2", "M3", "INT", "ENG"}, f"{rid}: invalid module")
        require(rid.startswith(f"CF-{module}-"), f"{rid}: module does not match id")
        require(isinstance(row.get("title"), str) and bool(row.get("title", "").strip()),
                f"{rid}: title required")
        require(row.get("baseline_status") in {"proposed", "accepted"}, f"{rid}: invalid baseline status")
        require(row.get("status") in states, f"{rid}: invalid delivery status")
        criteria = row.get("acceptance", [])
        require(isinstance(criteria, list) and bool(criteria)
                and all(isinstance(x, str) and x.strip() for x in criteria),
                f"{rid}: observable acceptance criteria required")
        for field in ("implementation", "verification"):
            values = row.get(field)
            require(isinstance(values, list), f"{rid}: {field} must be a list")
            if isinstance(values, list):
                for value in values:
                    check_path(value, f"{rid}/{field}")
        if row.get("status") in {"implemented", "verified"}:
            require(bool(row.get("implementation")), f"{rid}: implementation reference required")
        if row.get("status") == "verified":
            require(row.get("baseline_status") == "accepted", f"{rid}: baseline not accepted")
            require(bool(row.get("verification")), f"{rid}: verification evidence required")
        if row.get("status") == "deferred":
            require(bool(row.get("reason")), f"{rid}: deferral reason required")

    if errors:
        print("Repository checks failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"Repository checks passed: {len(module_ids)} modules, {len(rows)} requirements.")
    print("Scope: layout, registration and evidence paths only; no application tests or deployment.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
