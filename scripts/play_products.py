#!/usr/bin/env python3
"""Create or update the Play tip-jar products from play/products.json.

Idempotent against the Play Developer API: one-time tips are created or
replaced through the one-time products API (the legacy inappproducts
endpoint refuses apps on the current product model), the supporter
subscription is created or patched, and every purchase option or base plan
that is not yet active gets activated. Existing base plans keep the prices
Play already holds; changing a live price is a migration, not an edit, and
stays a deliberate Play Console action.

Regional prices start from Play's own converter and every write names the
regions version the converter priced at, so a region changing currency
(Bulgaria to the euro in 2026) cannot strand the catalog on an old version;
--regions-version pins one instead. The converter's charm prices (3.59 USD,
620 JPY) are then replaced by round local amounts: currencies near par
with the dollar take the CAD figure as-is, every other currency rounds to
a round figure of its own. A region whose ceiling is below a tip's price
gets the largest round amount under the ceiling Play reports.

Reads the service-account key path from PLAY_KEY_FILE. With --dry-run and
no key file, nothing leaves the machine: payloads print as they would be
sent, minus the regional prices that only Play can convert.
"""

import argparse
import json
import os
import re
import sys
import time
from decimal import Decimal
from pathlib import Path

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
REGIONS_VERSION = "2022/02"
PURCHASE_OPTION_ID = "buy"
PARITY_CURRENCIES = {"USD", "EUR", "GBP", "AUD", "NZD", "CHF", "SGD"}
ROUND_MANTISSAS = tuple(Decimal(m) for m in ("1", "1.5", "2", "2.5", "3", "4", "5", "6", "10"))
PRICE_BOUNDS = re.compile(r"Price for ([A-Z]{2}) must be between \D*([\d,.]+) and \D*([\d,.]+)")
MAX_CLAMPS = 12
TRANSIENT_ATTEMPTS = 5
SPEC = Path(__file__).resolve().parent.parent / "play" / "products.json"


class PlayApi:
    def __init__(self, key_file, dry_run, regions_version=None):
        self.dry_run = dry_run
        self.pinned_regions_version = regions_version
        self.regions_version = regions_version or REGIONS_VERSION
        self.session = None
        if key_file:
            import google.auth.transport.requests
            import requests
            from google.oauth2 import service_account

            creds = service_account.Credentials.from_service_account_file(key_file, scopes=[SCOPE])
            creds.refresh(google.auth.transport.requests.Request())
            self.session = requests.Session()
            self.session.headers["Authorization"] = f"Bearer {creds.token}"

    def request(self, method, url, **kwargs):
        for attempt in range(1, TRANSIENT_ATTEMPTS + 1):
            try:
                response = self.session.request(method, url, **kwargs)
            except OSError as failure:
                response, reason = None, str(failure)
            else:
                if response.status_code < 500:
                    return response
                reason = f"{response.status_code}: {response.text[:120]}"
            if attempt == TRANSIENT_ATTEMPTS:
                break
            delay = 2**attempt
            print(f"::notice::{method} {url.removeprefix(API + '/')} transient failure ({reason}); retrying in {delay}s")
            time.sleep(delay)
        if response is None:
            sys.exit(f"::error::{method} {url} kept failing: {reason}")
        return response

    def get(self, path):
        if self.session is None:
            print(f"  offline: skipping GET {path}")
            return None
        response = self.request("GET", f"{API}/{path}", timeout=30)
        if response.status_code == 404:
            return None
        if response.status_code != 200:
            print(f"::notice::GET {path} returned {response.status_code}: {response.text[:300]}")
            return None
        return response.json()

    def write(self, method, path, body):
        status, result = self.try_write(method, path, body)
        if status != 200:
            sys.exit(f"::error::{method} {path} failed with {status}: {result[:300]}")
        return result

    def try_write(self, method, path, body):
        print(f"  {method} {path}")
        print(json.dumps(body, indent=2, ensure_ascii=False))
        if self.dry_run or self.session is None:
            return 200, None
        response = self.request(method, f"{API}/{path}", json=body, timeout=60)
        if response.status_code != 200:
            return response.status_code, response.text
        return 200, response.json()

    def convert(self, package, money):
        if self.session is None:
            return None
        response = self.request(
            "POST", f"{API}/{package}/pricing:convertRegionPrices", json={"price": money}, timeout=60
        )
        if response.status_code != 200:
            sys.exit(f"::error::convertRegionPrices failed with {response.status_code}: {response.text[:300]}")
        converted = response.json()
        if self.pinned_regions_version is None:
            self.regions_version = converted.get("regionVersion", {}).get("version") or self.regions_version
        return converted


def money(price, currency):
    amount = Decimal(price)
    units = int(amount)
    return {"currencyCode": currency, "units": str(units), "nanos": int((amount - units) * 1_000_000_000)}


def amount(money_value):
    return Decimal(money_value.get("units") or 0) + Decimal(money_value.get("nanos") or 0) / 1_000_000_000


def round_local(value, floor=False):
    scale = Decimal(10) ** value.adjusted()
    mantissa = value / scale
    if not floor:
        return min(ROUND_MANTISSAS, key=lambda m: abs(m - mantissa)) * scale
    return max([m for m in ROUND_MANTISSAS if m <= mantissa] or [ROUND_MANTISSAS[0] / 10]) * scale


def local_price(converted, price):
    currency = converted["currencyCode"]
    if currency in PARITY_CURRENCIES:
        return money(price, currency)
    value = amount(converted)
    if value <= 0:
        return converted
    return money(round_local(value), currency)


def converted_prices(api, spec, price):
    converted = api.convert(spec["packageName"], money(price, spec["currency"]))
    if not converted:
        return [], None
    regions = [
        (entry["regionCode"], local_price(entry["price"], price)) for entry in converted["convertedRegionPrices"].values()
    ]
    other = converted["convertedOtherRegionsPrice"]
    return regions, {key: local_price(other[key], price) for key in ("usdPrice", "eurPrice")}


def tip_body(api, spec, product):
    regions, other = converted_prices(api, spec, product["price"])
    option = {
        "purchaseOptionId": PURCHASE_OPTION_ID,
        "buyOption": {"legacyCompatible": True},
        "regionalPricingAndAvailabilityConfigs": [
            {"regionCode": code, "availability": "AVAILABLE", "price": price} for code, price in regions
        ],
    }
    if other:
        option["newRegionsConfig"] = dict(other, availability="AVAILABLE")
    return {
        "packageName": spec["packageName"],
        "productId": product["sku"],
        "listings": [
            {"languageCode": lang, "title": title, "description": spec["tips"]["description"][lang]}
            for lang, title in product["title"].items()
        ],
        "purchaseOptions": [option],
    }


def base_plan(api, spec, plan):
    regions, other = converted_prices(api, spec, plan["price"])
    regional = [{"regionCode": code, "newSubscriberAvailability": True, "price": price} for code, price in regions]
    if other:
        other = dict(other, newSubscriberAvailability=True)
    body = {
        "basePlanId": plan["basePlanId"],
        "autoRenewingBasePlanType": {
            "billingPeriodDuration": "P1M",
            "gracePeriodDuration": "P30D",
            "resubscribeState": "RESUBSCRIBE_STATE_ACTIVE",
            "prorationMode": "SUBSCRIPTION_PRORATION_MODE_CHARGE_ON_NEXT_BILLING_DATE",
            "legacyCompatible": False,
        },
        "regionalConfigs": regional,
    }
    if other:
        body["otherRegionsConfig"] = other
    return body


def clamp_to_ceiling(body, error_text):
    found = PRICE_BOUNDS.search(error_text or "")
    if not found:
        return False
    region, ceiling = found.group(1), Decimal(found.group(3).replace(",", ""))
    for config in body["purchaseOptions"][0]["regionalPricingAndAvailabilityConfigs"]:
        if config["regionCode"] == region and amount(config["price"]) > ceiling:
            config["price"] = money(round_local(ceiling, floor=True), config["price"]["currencyCode"])
            print(f"  {region}: clamped to {amount(config['price'])} under a ceiling of {ceiling}")
            return True
    return False


def sync_tips(api, spec):
    created = updated = activated = 0
    for product in spec["tips"]["products"]:
        package, sku = spec["packageName"], product["sku"]
        existing = api.get(f"{package}/oneTimeProducts/{sku}")
        body = tip_body(api, spec, product)
        path = (
            f"{package}/onetimeproducts/{sku}?updateMask=listings,purchaseOptions"
            f"&regionsVersion.version={api.regions_version}&allowMissing=true"
        )
        for _ in range(MAX_CLAMPS):
            status, result = api.try_write("PATCH", path, body)
            if status == 200:
                break
            if not clamp_to_ceiling(body, result):
                sys.exit(f"::error::PATCH {path} failed with {status}: {result[:300]}")
        else:
            sys.exit(f"::error::{sku}: still outside a regional price ceiling after {MAX_CLAMPS} clamps")
        if existing is None:
            created += 1
        else:
            updated += 1
        states = {option["purchaseOptionId"]: option.get("state") for option in (result or {}).get("purchaseOptions", [])}
        if states.get(PURCHASE_OPTION_ID) == "ACTIVE":
            continue
        activate = {"packageName": package, "productId": sku, "purchaseOptionId": PURCHASE_OPTION_ID}
        api.write(
            "POST",
            f"{package}/oneTimeProducts/{sku}/purchaseOptions:batchUpdateStates",
            {"requests": [{"activatePurchaseOptionRequest": activate}]},
        )
        activated += 1
    return created, updated, activated


def sync_subscription(api, spec):
    sub = spec["subscription"]
    package, product_id = spec["packageName"], sub["productId"]
    existing = api.get(f"{package}/subscriptions/{product_id}")
    kept = {plan["basePlanId"]: plan for plan in (existing or {}).get("basePlans", [])}
    plans = [kept.get(plan["basePlanId"]) or base_plan(api, spec, plan) for plan in sub["basePlans"]]
    body = {
        "packageName": package,
        "productId": product_id,
        "listings": [
            {"languageCode": lang, "title": title, "description": sub["description"][lang]}
            for lang, title in sub["title"].items()
        ],
        "basePlans": plans,
    }
    if existing is None:
        result = api.write(
            "POST", f"{package}/subscriptions?productId={product_id}&regionsVersion.version={api.regions_version}", body
        )
    else:
        result = api.write(
            "PATCH",
            f"{package}/subscriptions/{product_id}?updateMask=listings,basePlans"
            f"&regionsVersion.version={api.regions_version}&allowMissing=true",
            body,
        )
    states = {plan["basePlanId"]: plan.get("state") for plan in (result or {}).get("basePlans", [])}
    activated = 0
    for plan in sub["basePlans"]:
        if states.get(plan["basePlanId"]) == "ACTIVE":
            continue
        api.write("POST", f"{package}/subscriptions/{product_id}/basePlans/{plan['basePlanId']}:activate", {})
        activated += 1
    return existing is None, activated


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="print payloads, write nothing")
    parser.add_argument("--regions-version", help="pin a Play regions version instead of the one the converter answers with")
    args = parser.parse_args()

    key_file = os.environ.get("PLAY_KEY_FILE")
    if not key_file and not args.dry_run:
        sys.exit("::error::PLAY_KEY_FILE is not set; pass --dry-run to build payloads offline")
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    api = PlayApi(key_file, args.dry_run, args.regions_version)

    print("== One-time tips")
    created, updated, tips_activated = sync_tips(api, spec)
    print("== Supporter subscription")
    sub_created, plans_activated = sync_subscription(api, spec)
    verb = "would be" if args.dry_run or api.session is None else "were"
    print(
        f"Tips: {created} {verb} created, {updated} {verb} updated, {tips_activated} {verb} activated. "
        f"Subscription: {'created' if sub_created else 'patched'}, {plans_activated} base plan(s) {verb} activated."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
