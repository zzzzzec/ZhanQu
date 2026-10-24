import json
import sqlite3
import sys
from pathlib import Path

directory = Path(sys.argv[1]).resolve()
database = directory / "databases" / "zhanqu.db"
connection = sqlite3.connect(str(database))
connection.row_factory = sqlite3.Row
records = []
summaries = []
for row in connection.execute("SELECT * FROM notes WHERE sourcePackage IN (?, ?, ?) ORDER BY capturedAt DESC", ("com.xingin.xhs", "com.zhihu.android", "tv.danmaku.bili")):
    record = dict(row)
    raw = json.loads(record["rawJson"])
    record["decoded"] = raw
    records.append(record)
    comments = raw.get("comments", [])
    pages = raw.get("pages", [])
    answers = raw.get("answers", [])
    summaries.append({
        "id": record["id"], "title": record["title"], "status": record["status"],
        "sourcePackage": record["sourcePackage"], "captureKind": raw.get("captureKind"),
        "video": raw.get("video"),
        "answers": len(answers), "answerCharacters": [len(a.get("text", "")) for a in answers],
        "answerObservations": [len(a.get("observations", [])) for a in answers],
        "answerEndReached": [a.get("endReached", False) for a in answers],
        "reportedPages": raw.get("reportedPages"), "pages": len(pages),
        "pageIndices": [page.get("pageIndex") for page in pages],
        "pageHashes": [page.get("contentHash") for page in pages],
        "pageStates": [page.get("state") for page in pages],
        "ocrCharacters": [len(page.get("text", "")) for page in pages],
        "displayedCommentCount": raw.get("displayedCount"), "comments": len(comments),
        "parents": sum(not comment.get("isReply", False) for comment in comments),
        "replies": sum(comment.get("isReply", False) for comment in comments),
        "unresolvedReplies": sum(comment.get("parentUnresolved", False) for comment in comments),
        "commentKeys": sorted({key for comment in comments for key in comment}),
        "endReached": raw.get("endReached"), "expandedReplyGroups": raw.get("expandedReplyGroups"),
        "unexpandedGroups": raw.get("unexpandedGroups"), "errors": raw.get("errors", []),
    })
connection.close()
(directory / "records.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
(directory / "summary.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"directory": str(directory), "notes": summaries}, ensure_ascii=False, indent=2))
