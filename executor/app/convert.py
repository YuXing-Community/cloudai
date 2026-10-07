"""Excel / CSV 转 Markdown 表格，供记忆库检索与浏览"""
from __future__ import annotations

import csv
from pathlib import Path

MAX_ROWS = 2000
MAX_COLS = 50


def _cell(v) -> str:
    if v is None:
        return ""
    s = str(v).replace("|", "\\|").replace("\n", "<br>").strip()
    if s.endswith(".0") and s[:-2].lstrip("-").isdigit():
        s = s[:-2]
    return s


def _table(rows: list[list]) -> list[str]:
    rows = [r[:MAX_COLS] for r in rows if any(c not in (None, "") for c in r)]
    if not rows:
        return ["（空表）"]
    width = max(len(r) for r in rows)
    header = [(_cell(c) or f"列{i + 1}") for i, c in enumerate(rows[0] + [None] * (width - len(rows[0])))]
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * width]
    for r in rows[1:MAX_ROWS + 1]:
        r = r + [None] * (width - len(r))
        lines.append("| " + " | ".join(_cell(c) for c in r) + " |")
    if len(rows) - 1 > MAX_ROWS:
        lines.append(f"\n> 仅展示前 {MAX_ROWS} 行，共 {len(rows) - 1} 行")
    return lines


def to_markdown(file_path: str) -> str:
    p = Path(file_path)
    ext = p.suffix.lower()
    out = [f"# {p.stem}", "", f"> 由 `{p.name}` 自动转换", ""]
    if ext in (".xlsx", ".xlsm"):
        import openpyxl
        wb = openpyxl.load_workbook(p, read_only=True, data_only=True)
        for ws in wb.worksheets:
            out += [f"## {ws.title}", ""] + _table([list(r) for r in ws.iter_rows(values_only=True)]) + [""]
    elif ext == ".xls":
        import xlrd
        wb = xlrd.open_workbook(str(p))
        for sh in wb.sheets():
            out += [f"## {sh.name}", ""] + _table([sh.row_values(i) for i in range(sh.nrows)]) + [""]
    elif ext in (".csv", ".tsv"):
        raw = p.read_bytes()
        text = None
        for enc in ("utf-8-sig", "gbk", "latin-1"):
            try:
                text = raw.decode(enc)
                break
            except Exception:
                continue
        rows = list(csv.reader((text or "").splitlines(), delimiter="\t" if ext == ".tsv" else ","))
        out += _table(rows)
    else:
        raise ValueError(f"不支持转换的文件类型: {ext}")
    return "\n".join(out) + "\n"
