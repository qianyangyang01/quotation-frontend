# Approved quotation design assets

These three PNG regions are lossless extracts from the customer-approved design
`codex-clipboard-7957711a-ba27-4a8a-b9f7-d931f4c30548.png` (1536 × 1024).
They are static application assets, not generated customer quotations.

- `brand.png`: rectangle (0, 0, 1226, 164), preserving the approved logo and title.
- `table-header.png`: rectangle (22, 179, 1493, 90). The renderer replaces the
  Shipping Time and quantity cells; the reference expiry label is never exported.
- `notes.png`: rectangle (22, 638, 1493, 340), containing the complete four approved
  English notes. Their accessible text is in `CUSTOMER_QUOTE_NOTES`.

The renderer never uses the reference example data, agent, date, or sample footer.
Customer PNGs are created on demand in browser memory only. They are neither
uploaded nor saved in localStorage, IndexedDB, quotation records, or image history.
