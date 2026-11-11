---
name: travel-search
description: Search and compare flights or China railway services, verify schedules and prices, and present results as compact flight or 12306-style cards.
---

# Travel search

Use this skill when the user asks to find, compare, or check flights, trains, or high-speed rail.

1. Confirm the route and travel date from the user's request or conversation. Ask a short follow-up only when a missing value prevents a useful search; never silently invent a date, passenger, station, or airport.
2. Search current sources with the available web or browser tools. Prefer airline/airport sources for flights and China Railway's 12306 site or app for trains. Distinguish a search result from a live booking result; do not claim a fare, seat, or remaining-ticket count is live unless the source verifies it.
3. Compare a small set of useful options. Keep departure and arrival local times, station/airport names, duration, carrier and service number, cabin/seat class, price, and availability exactly as supported by the source. Mark unavailable fields as unknown in the card data; do not estimate them.
4. Give the user a short sentence, then emit the verified results in one of the following fenced JSON blocks so the chat can render a visual card. Keep each list to at most 8 options and include the source URL for every option (or one shared `source` when all options came from the same page).

For flights:

```miniagent-flight
{"route":{"from":"Beijing","to":"Zhengzhou","date":"YYYY-MM-DD"},"flights":[{"airline":"Carrier","flightNo":"XX1234","departureTime":"08:30","departureAirport":"Airport/terminal","arrivalTime":"10:10","arrivalAirport":"Airport/terminal","duration":"1h40m","price":"¥520","cabin":"Economy","source":{"label":"Source","url":"https://example.com"}}]}
```

For trains / high-speed rail:

```miniagent-train
{"route":{"from":"Beijing South","to":"Shanghai Hongqiao","date":"YYYY-MM-DD"},"trains":[{"trainNo":"G1","operator":"12306","departureTime":"07:00","departureStation":"Beijing South","arrivalTime":"11:30","arrivalStation":"Shanghai Hongqiao","duration":"4h30m","seats":[{"name":"二等座","remaining":"有票","price":"¥553"},{"name":"一等座","remaining":"候补","price":"¥933"}],"source":{"label":"12306","url":"https://www.12306.cn/"}}]}
```

Use a short value such as `未核实` for unknown price or availability. Only include booking links that point to the source actually consulted. Do not emit example values above as search results. If no reliable current results are available, say so plainly and provide an official search link instead of filling a card with guesses.
