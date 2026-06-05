#!/usr/bin/env python3
"""HTML 리포트 생성 — 카테고리별 가게 랭킹 + 근거 글 펼침."""
import html
from datetime import datetime

from blog_score.dictionaries import CATEGORY_LABELS

_CSS = """
*{box-sizing:border-box}body{font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo',sans-serif;
margin:0;background:#faf7f2;color:#2b2320}
header{background:#7a4a2b;color:#fff;padding:20px 28px}
header h1{margin:0 0 4px;font-size:20px}header .meta{font-size:13px;opacity:.85}
.tabs{display:flex;flex-wrap:wrap;gap:6px;padding:14px 28px;background:#fff;position:sticky;top:0;
border-bottom:1px solid #eee;z-index:5}
.tab{padding:6px 12px;border-radius:18px;background:#f0e7dd;color:#7a4a2b;font-size:13px;cursor:pointer;
border:none}.tab.active{background:#7a4a2b;color:#fff}
section{padding:16px 28px 40px}
.cat{display:none}.cat.active{display:block}
.cat h2{font-size:16px;margin:6px 0 12px}
table{width:100%;border-collapse:collapse;background:#fff;border-radius:10px;overflow:hidden;
box-shadow:0 1px 3px rgba(0,0,0,.06)}
th,td{padding:10px 12px;text-align:left;font-size:13px;border-bottom:1px solid #f0ece6}
th{background:#f7f0e8;color:#6b5444;font-weight:600}
tr.store-row{cursor:pointer}tr.store-row:hover{background:#fdf8f1}
.rank{font-weight:700;color:#7a4a2b;width:36px}
.score{font-weight:700}.bar{display:inline-block;height:8px;background:#d98b4a;border-radius:4px;vertical-align:middle;margin-left:6px}
.muted{color:#9a8b7d;font-size:12px}
.posts{display:none;background:#fbf6ef}.posts.open{display:table-row}
.posts td{padding:0}
.post{padding:8px 16px;border-bottom:1px dashed #e8ddd0;font-size:12px}
.post a{color:#a85a2a;text-decoration:none}.post a:hover{text-decoration:underline}
.tag{display:inline-block;padding:1px 7px;border-radius:10px;font-size:11px;margin-right:5px}
.pos{background:#dcf0dc;color:#2e7d32}.neg{background:#fbdada;color:#c62828}.neu{background:#eee;color:#666}
.ad{background:#ffe6c2;color:#9a5b00}
.empty{color:#aaa;padding:20px;text-align:center}
.leaderboard{margin:16px 28px 0;background:#fff;border-radius:10px;padding:14px 18px;box-shadow:0 1px 3px rgba(0,0,0,.06)}
.leaderboard h3{margin:0 0 10px;font-size:14px;color:#7a4a2b}
.lb-row{display:flex;align-items:center;gap:10px;padding:3px 0;font-size:13px}
.lb-rank{width:20px;font-weight:700;color:#d98b4a;text-align:right}
.lb-name{width:130px}.lb-bar{height:9px;background:#7a4a2b;border-radius:5px;min-width:2px}
.lb-meta{font-size:11px}
"""

_JS = """
function showCat(id,btn){
 document.querySelectorAll('.cat').forEach(e=>e.classList.remove('active'));
 document.querySelectorAll('.tab').forEach(e=>e.classList.remove('active'));
 document.getElementById(id).classList.add('active');btn.classList.add('active');
}
function toggle(id){var r=document.getElementById(id);r.classList.toggle('open');}
"""


def _esc(s) -> str:
	return html.escape(str(s if s is not None else ""))


def _post_block(p: dict) -> str:
	senti = p.get("sentiment", "NEU")
	cls = {"POS": "pos", "NEG": "neg", "NEU": "neu"}.get(senti, "neu")
	tags = f'<span class="tag {cls}">{senti}</span>'
	if p.get("is_ad"):
		tags += f'<span class="tag ad">광고 제외: {_esc(p.get("ad_reason"))}</span>'
	cats = " ".join(f'{CATEGORY_LABELS.get(c, c)}×{n}' for c, n in p.get("cats", {}).items())
	return (f'<div class="post">{tags}<a href="{_esc(p["url"])}" target="_blank">'
			f'{_esc(p["title"])}</a> <span class="muted">{_esc(p.get("date"))} · {_esc(cats)}</span></div>')


def build_html(results: dict) -> str:
	"""results: {run, snapshot_at, store_count, categories:{cat:[ranked rows]}, posts_by_store:{sid:[...]}}"""
	region = results.get("region", "망원동")
	cats = results["categories"]
	posts_by = results.get("posts_by_store", {})
	tabs, sections = [], []
	for i, (cat, rows) in enumerate(cats.items()):
		label = CATEGORY_LABELS.get(cat, cat)
		active = " active" if i == 0 else ""
		tabs.append(f'<button class="tab{active}" onclick="showCat(\'cat-{cat}\',this)">'
					f'{_esc(label)} <span class="muted">{len(rows)}</span></button>')
		if not rows:
			body = '<div class="empty">데이터 없음</div>'
		else:
			trs = []
			maxv = max((r["normalized"] for r in rows), default=100) or 100
			for rank, r in enumerate(rows, 1):
				sid = r["store_id"]
				rid = f"p-{cat}-{sid}"
				barw = int(r["normalized"] / maxv * 120)
				trs.append(
					f'<tr class="store-row" onclick="toggle(\'{rid}\')">'
					f'<td class="rank">{rank}</td>'
					f'<td>{_esc(r["name"])}<div class="muted">{_esc(r.get("address",""))}</div></td>'
					f'<td class="score">{r["normalized"]}<span class="bar" style="width:{barw}px"></span></td>'
					f'<td>{r["mention_count"]}<span class="muted"> (최근 {r.get("recent_count",0)})</span></td>'
					f'<td>{_esc(r.get("review_count") if r.get("review_count") is not None else "-")}</td>'
					f'</tr>')
				relevant = [p for p in posts_by.get(str(sid), []) if cat in p.get("cats", {})]
				inner = "".join(_post_block(p) for p in relevant[:8]) or '<div class="post muted">근거 글 없음</div>'
				trs.append(f'<tr class="posts" id="{rid}"><td colspan="5">{inner}</td></tr>')
			body = (f'<table><thead><tr><th>#</th><th>가게</th><th>점수</th>'
					f'<th>언급글</th><th>리뷰수</th></tr></thead><tbody>{"".join(trs)}</tbody></table>')
		sections.append(f'<div class="cat{active}" id="cat-{cat}"><h2>{_esc(label)}</h2>{body}</div>')

	# 카테고리 순위 리더보드
	leaderboard = ""
	cr = results.get("category_ranking")
	if cr:
		chips = []
		mx = max((c["metric"] for c in cr), default=1) or 1
		for i, c in enumerate(cr, 1):
			lbl = CATEGORY_LABELS.get(c["cat"], c["cat"])
			w = int(c["metric"] / mx * 100)
			chips.append(
				f'<div class="lb-row"><span class="lb-rank">{i}</span>'
				f'<span class="lb-name">{_esc(lbl)}</span>'
				f'<span class="lb-bar" style="width:{w}px"></span>'
				f'<span class="lb-meta muted">{c["store_count"]}곳</span></div>')
		leaderboard = (f'<div class="leaderboard"><h3>🔥 {_esc(region)}에서 핫한 빵 카테고리</h3>'
					   f'{"".join(chips)}</div>')

	gen = datetime.now().strftime("%Y-%m-%d %H:%M")
	return f"""<!doctype html><html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{_esc(region)} 빵집 블로그 점수 리포트</title><style>{_CSS}</style></head><body>
<header><h1>🥐 {_esc(region)} 빵집 — 블로그 크롤링 점수 리포트</h1>
<div class="meta">스냅샷 {_esc(results.get('snapshot_at'))} · 가게 {results.get('store_count')}곳 ·
크롤 글 {results.get('total_posts',0)} (광고제외 {results.get('ad_excluded',0)}) · 생성 {gen}</div></header>
{leaderboard}
<div class="tabs">{''.join(tabs)}</div>
<section>{''.join(sections)}</section>
<script>{_JS}</script></body></html>"""
