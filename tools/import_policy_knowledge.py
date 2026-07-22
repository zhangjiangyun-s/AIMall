import argparse
import hashlib
import os
import shutil
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DOC_DIR = ROOT / "docs" / "policy_knowledge"
MYSQL_EXE = shutil.which("mysql") or "mysql"


def load_env() -> dict[str, str]:
    env = dict(os.environ)
    env_file = ROOT / ".env"
    if not env_file.exists():
        return env
    for line in env_file.read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if not text or text.startswith("#") or "=" not in text:
            continue
        key, value = text.split("=", 1)
        env.setdefault(key.strip(), value.strip().strip('"').strip("'"))
    return env


def parse_front_matter(text: str) -> tuple[dict[str, str], str]:
    normalized = text.replace("\ufeff", "").replace("\r\n", "\n").replace("\r", "\n")
    if not normalized.startswith("---\n"):
        return {}, normalized.strip()
    end = normalized.find("\n---\n", 4)
    if end < 0:
        return {}, normalized.strip()
    raw_meta = normalized[4:end]
    body = normalized[end + len("\n---\n") :].strip()
    meta: dict[str, str] = {}
    for line in raw_meta.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        meta[key.strip()] = value.strip()
    return meta, body


def sql_quote(value: object | None) -> str:
    if value is None:
        return "NULL"
    text = str(value)
    if text == "":
        return "NULL"
    escaped = (
        text.replace("\\", "\\\\")
        .replace("\0", "\\0")
        .replace("'", "''")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
    )
    return f"'{escaped}'"


def int_version(value: str | None) -> int:
    if not value:
        return 1
    digits = "".join(ch for ch in value if ch.isdigit() or ch == ".")
    if not digits:
        return 1
    first = digits.split(".", 1)[0]
    return int(first) if first.isdigit() else 1


def visibility_scope(value: str | None) -> str:
    normalized = (value or "USER").strip().upper()
    mapping = {
        "USER": "PUBLIC_USER",
        "PUBLIC": "PUBLIC_USER",
        "PUBLIC_USER": "PUBLIC_USER",
        "CUSTOMER_SERVICE": "CUSTOMER_SERVICE",
        "ADMIN": "ADMIN",
    }
    return mapping.get(normalized, "PUBLIC_USER")


def build_sql(doc_dir: Path) -> tuple[str, int]:
    statements = [
        "SET NAMES utf8mb4;",
        "START TRANSACTION;",
    ]
    count = 0
    for path in sorted(doc_dir.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        meta, body = parse_front_matter(text)
        doc_code = meta.get("doc_code") or path.stem
        title = meta.get("title") or first_markdown_title(body) or path.stem
        source_type = meta.get("source_type") or "POLICY"
        category = meta.get("category") or ""
        scope = visibility_scope(meta.get("visibility_scope"))
        effective_time = meta.get("effective_time") or None
        expire_time = meta.get("expire_time") or None
        version = int_version(meta.get("version"))
        product_category_scope = meta.get("product_category_scope") or "ALL"
        risk_level = meta.get("risk_level") or "LOW"
        source_hash = hashlib.sha256(body.encode("utf-8")).hexdigest()
        source_uri = str(path.relative_to(ROOT)).replace("\\", "/")
        tags = ",".join(item for item in [category, product_category_scope, risk_level] if item)

        fields = {
            "title": title,
            "source_type": source_type,
            "content": body,
            "status": "ENABLED",
            "version": version,
            "source_system": "policy_markdown",
            "source_uri": source_uri,
            "source_hash": source_hash,
            "external_doc_id": doc_code,
            "visibility_scope": scope,
            "tenant_id": "default",
            "tags": tags,
            "effective_time": effective_time,
            "expire_time": expire_time,
        }

        set_clause = ", ".join(f"`{key}` = {sql_quote(value)}" for key, value in fields.items() if key != "external_doc_id")
        statements.append(
            "UPDATE `knowledge_doc` "
            f"SET {set_clause}, `updated_at` = NOW() "
            f"WHERE `external_doc_id` = {sql_quote(doc_code)};"
        )

        columns = ", ".join(f"`{key}`" for key in fields)
        values = ", ".join(sql_quote(value) for value in fields.values())
        statements.append(
            f"INSERT INTO `knowledge_doc` ({columns}, `created_at`, `updated_at`) "
            f"SELECT {values}, NOW(), NOW() FROM DUAL "
            f"WHERE NOT EXISTS (SELECT 1 FROM `knowledge_doc` WHERE `external_doc_id` = {sql_quote(doc_code)});"
        )
        count += 1

    statements.append("COMMIT;")
    return "\n".join(statements), count


def first_markdown_title(body: str) -> str | None:
    for line in body.splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description="Import AIMall policy Markdown docs into knowledge_doc.")
    parser.add_argument("--doc-dir", default=str(DEFAULT_DOC_DIR))
    parser.add_argument("--database", default="aimall")
    args = parser.parse_args()

    doc_dir = Path(args.doc_dir)
    if not doc_dir.exists():
        raise SystemExit(f"Document directory does not exist: {doc_dir}")

    env = load_env()
    mysql = MYSQL_EXE
    username = env.get("AIMALL_DB_USERNAME", "root")
    password = env.get("AIMALL_DB_PASSWORD") or env.get("MYSQL_ROOT_PASSWORD", "123456")

    sql, count = build_sql(doc_dir)
    command = [
        str(mysql),
        "--default-character-set=utf8mb4",
        f"-u{username}",
        f"-p{password}",
        "-D",
        args.database,
    ]
    result = subprocess.run(command, input=sql, text=True, encoding="utf-8", capture_output=True, check=False)
    if result.stdout:
        print(result.stdout)
    if result.stderr:
        print(result.stderr)
    if result.returncode != 0:
        raise SystemExit(result.returncode)
    print(f"Imported policy docs: {count}")


if __name__ == "__main__":
    main()
