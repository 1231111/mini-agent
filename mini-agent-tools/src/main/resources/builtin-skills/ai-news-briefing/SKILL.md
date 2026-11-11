---
name: ai-news-briefing
description: Prepare a dated, deduplicated AI news briefing with verified publication dates and source links.
---

# AI news briefing

Use this skill for requests such as today's AI news, recent model releases, or a weekly industry roundup.

1. Resolve relative dates using the user's local date and timezone. State the exact coverage window at the top, and pass the exact inclusive `dateFrom` and `dateTo` (YYYY-MM-DD) to every `web_search` call. For "today", both values must be today's local date.
2. Search for several focused topics, such as model releases, research, products, policy, and major company news. Do not fill a category if there is no credible news.
3. Use `web_search` to discover reports and inspect each result's `publication_date`; this is only search-provider metadata and is not verified against the article. Use `web_extract` on every candidate to verify the original page's `publication_date` and `date_verified`. Include only items whose original-page publication date is verified to fall inside the requested window. Prefer company/research-lab announcements, papers, and regulators for primary facts; use reputable reporting for context. Never treat `dateLastCrawled`, crawl time, search result date alone, or the event date as the publication date.
4. Deduplicate stories that appear across outlets. Record the publication date separately from the event date and exclude items outside the requested window unless clearly labeled as background.
5. Summarize each item in a few sentences: what happened, why it matters, and any important limitation. Attach a source title and URL to every item. Do not present speculation or a search snippet as confirmed news.

Finish with a short synthesis of the strongest themes, and explicitly say if the search found too few well-supported items.
