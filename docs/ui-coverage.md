# UI coverage

**Status:** written 2026-10-04, when the UI was first completed; the
browser walk-through it owed was done on 2026-10-06 (below).

This records where each item of the UI checklist in `spec/domain.md` §13 is
met, and how it was checked. `docs/counterpart-metrics.md` in tadmor asks
for a walk-through, item by item, before a counterpart's figures count;
this is the record to walk through.

**How it was checked.** "Test" means `src/test/java/.../ui/UiFlowsTest.java`
or `LoginUiTest.java` drives it over HTTP as a browser would: real
sessions, forms posted with their CSRF tokens, the server's responses read
back. "Smoke" means the screen was rendered against a live server with
data and inspected, but no test asserts it. "Script" marks behavior that
lives in `src/main/resources/static/app.js`: its arithmetic was checked
against the server's (domain §2) in Node on the spec's examples. "Browser"
means a scripted walk-through in headless Chromium on 2026-10-06, at
`4fad909`, against a server holding the conformance suite's data. It used
tadmor's Playwright install from outside this repo, so it is not
repeatable from here. It signed in, then loaded every screen reachable by
links (151 pages over 106 routes) with no script error, console error, or
failed request. On a new sales invoice and a new purchase bill it chose a
party, which set the party's currency, and a product, which filled the
description, price, account, tax code, and rate. It added a hand-written
line with awkward decimals and a tax code, which set the rate, added a
third line and removed it, saved, and found the previewed total equal to
the saved one to the ten-thousandth. Then it deleted the draft, dismissing
the confirmation once (kept) and accepting it once (deleted).

## General

| Item | Where | Checked |
| ---- | ----- | ------- |
| G1 | `/login`; every other page redirects there without a session | Test |
| G2 | Name and Sign out in the header of every page | Test |
| G3 | The sidebar on every page | Test (every link opens) |
| G4 | Users, unpost, reopen, and year-end hidden from non-administrators; enforced in `SecurityConfig` | Test |
| G5 | Refusals re-show the form or detail screen with the server's message | Test |
| G6 | `data-confirm` on every delete (documents, payments, orders, movements, statements and their lines, exchange rates) | Test (attribute present); Script, Browser (dialog) |
| G7 | Amounts exact, grouped, never rounded (`ui/Format`); currency beside document amounts | Test |
| G8 | Not-found page for unknown addresses and records | Test |

## Home

| Item | Where | Checked |
| ---- | ----- | ------- |
| H1 to H5 | `/` | Test (content present), Smoke (figures) |

## Master data

| Item | Where | Checked |
| ---- | ----- | ------- |
| M1 to M5 | `/organizations`, `/customers`, `/suppliers`, `/products`, `/accounts`, `/tax-codes`, `/payment-terms`, `/warehouses` (generic list and form, `ui/Resources`) | Test (create, refusal, parent picker), Smoke (every list and form) |
| M6 | Active column in each list; Active checkbox on edit only | Smoke |
| M7 | `/users`, with Reset password per user | Smoke (refusals come from the users API's tests) |
| M8 | `/settings`, read-only for non-administrators | Test |

## Documents, payments, orders, inventory

| Item | Where | Checked |
| ---- | ----- | ------- |
| D1 to D4, D6, D7 | `/sales-invoices`, `/purchase-bills`, `/sales-credit-notes`, `/purchase-credit-notes` | Test (form to posted to unposted, refusal, email 501, PDF link) |
| D2 previews and fills | Line editor | Script, Browser |
| D5 | Credit note detail, "Applied to" | Smoke |
| P1 to P4 | `/customer-payments`, `/supplier-payments` | Test |
| O1 to O4, O7 | `/sales-orders`, `/purchase-orders` | Test |
| O5, O6 | `/{orders}/{id}/invoice` (or `bill`), `/ship` (or `receive`) | Test (invoice, zero-quantity refusal); Smoke (ship) |
| S1 to S3 | `/stock-movements` | Test (receipt posted against GRNI, issue signed) |

## Reports and accounting

| Item | Where | Checked |
| ---- | ----- | ------- |
| R1 to R4, R7, R8 | `/reports/...` | Smoke (figures come from the report API's tests) |
| R5 | `/accounts/{id}/ledger` | Smoke |
| R6 | `/journal-entries/{id}` | Test |
| A1 | `/periods`, period and year forms | Test (next period proposed, toggle) |
| A2 | `/year-end/{id}/close`, Reopen on `/periods` | Test (page and gating) |
| A3 | `/exchange-rates` | Smoke |
| A4, A5 | `/bank-statements` | Test (import, refusal, auto-match, reconcile, reopen offered) |
