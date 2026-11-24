# Official PDF layout regression

`lh-multichild-two-up.pdf` is the unchanged official **2026년 다자녀 전세임대 입주자 수시모집** PDF, LH file `68868379`:

https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68868379

It has seven physical PDF pages containing fourteen printed pages. Physical page 1 contains an independent left eligibility/ranking page and a right page with nested income, asset and household tables. Its original SHA-256 is `8a6023cb889535d1362a8b7915c80d6997281b37048f0ad77ee0af02d58f6a91` (the production source evidence identifier, not a generated expectation).

The regression verifies reading order, merged/nested cell relationships and physical page evidence locations through `DocumentTextParser`. Eligibility interpretation and publication approval are outside this extraction test.

`lh-multichild-2026.hwpx` is the unchanged matching official HWPX, LH file `68868375`:

https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68868375

It supplies the actual paragraph-style dependency missing from plain XML text: in `Contents/section0.xml`, the public-employment income source cell refers to paragraph style `183`; `Contents/header.xml` explicitly gives that style bullet `1`, whose character is `-`. The regression checks that the HWPX preserves the same visible bullet as printed page 10 (physical PDF page 5). It does not accept whole-document equivalence by flattening tables.

The same HWPX contains five inline right arrows in the application-process table. The extraction regression reads the actual embedded JPEG through its manifest reference and verifies its original cell positions. Rotated, cropped, flipped, recolored or floating versions do not become assumed right arrows.

## Official automatic-numbering sources

These are unchanged LH HWPX attachments. Number prefixes come from the document's header styles and manifest-ordered paragraphs, not text inserted into fixtures:

- `lh-inje-69338134.hwpx`: https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69338134 — income table heading `1. 신분별 입주소득 기준`; independently visible on physical page 5 of official PDF `69338139`.
- `lh-osan-68931436.hwpx`: https://apply.lh.or.kr/lhapply/lhFile.do?fileid=68931436 — two numbered paragraphs in the qualification-relaxation confirmation form, `1. (공통)` and `2. (소득완화)`. These form paragraphs were not located in the separately collected PDF `69203687`; preserving the HWPX does not establish equivalence with that PDF.
- `lh-daegu-69028523.hwpx`: https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69028523 — a stored editor memo has numbered lines `1. 예비신혼부부 여부 추가` and `2. 세대원정보 추가`. It remains inside MEMO source markers; it is not an official application requirement.

`lh-sangok-69385652.hwpx` is the unchanged official Incheon Sangok A1 HWPX:

https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69385652

Its first-page application-process table has four inline JPEG arrows in columns 2, 4, 6 and 8, each spanning two rows. Official PDF `69385666`, physical page 1, independently displays all four arrows. The picture's source-image dimensions and full clipping rectangle are 1740 × 2760, while its placed shape coordinates (`orgSz`/`imgRect`) are 7740 × 9780. The regression preserves the full image by checking clipping against source-image coordinates, separately from placed shape geometry; it never treats an actually cropped image as complete.

The same source supplies two additional layout contracts. Rental-condition table 8 has separate sign and amount cells with explicitly absent adjoining borders; these source border definitions are retained as CELL metadata rather than guessed from the text. The income/asset allowance box (table 13) is anchored before its introduction in XML but explicitly placed below that paragraph: `TOP_AND_BOTTOM`, paragraph-relative top offset 4564, top margin 141, and cached paragraph text extent 4404. It therefore follows the introduction in extracted reading order, matching physical PDF page 4. Missing coordinates, overlapping placement and multiple floating tables retain their original source order.

`lh-sangok-69385666.pdf` is its unchanged official 28-page PDF:

https://apply.lh.or.kr/lhapply/lhFile.do?fileid=69385666

Physical page 1 contains nested step boxes and four raster arrows; the third picture has a fractional source-pixel clip. Page 2 contains tables with omitted outside vertical strokes, explicit upper/lower borders and double horizontal borders. Its first table has four rows and twelve columns. Its second has six visible rows and nine ruled columns; the HWPX has an additional unruled sign/amount split. Printed page numbers 1–28 occupy separate bottom-centred positions. Regressions retain table subjects, values, step/date order and literal evidence page numbers rather than accepting character counts as equivalence.

Physical page 10 has four smaller online-application arrows, matching HWPX `BinData/image3.jpg`. PDF recompression introduces bright and dark edge ringing; the reader compares recovered fill against the actual central fill, while retaining raw bounds and internal-text rejection. Physical page 26 uses short dashed cell boundaries (0.48-point strokes with 0.72-point gaps, or 0.36 with 0.48), rather than one long rule. The regression keeps the actual room/item/specification relationships and rejects a synthetic dashed boundary with a missing middle stroke. The whole-document comparison also exercises paragraphs split inside table rows on successive physical pages; the opposite complete HWPX table remains the witness for every joined value and span.
