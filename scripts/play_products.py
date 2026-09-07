#!/usr/bin/env python3
"""Create or update the Play tip-jar products from play/products.json.

Idempotent against the Play Developer API: one-time tips are inserted or
replaced, the supporter subscription is created or patched, and every base
plan that is not yet active gets activated. Existing base plans keep the
prices Play already holds; changing a live price is a migration, not an
edit, and stays a deliberate Play Console action.

Reads the service-account key path from PLAY_KEY_FILE. With --dry-run and
no key file, nothing leaves the machine: payloads print as they would be
sent, minus the regional prices that only Play can convert.
"""

import argparse
import json
import os
import sys
from decimal import Decimal
from pathlib import Path

API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
SCOPE = "https://www.googleapis.com/auth/androidpublisher"
REGIONS_VERSION = "2022/02"
SPEC = Path(__file__).resolve().parent.parent / "play" / "products.json"


class PlayApi:
    def __init__(self, key_file, dry_run):
        self.dry_run = dry_run
        self.session = None
        if key_file:
            import google.auth.transport.requests
            import requests
            from google.oauth2 import service_account

            creds = service_account.Credentials.from_service_account_file(key_file, scopes=[SCOPE])
            creds.refresh(google.auth.transport.requests.Request())
            self.session = requests.Session()
            self.session.headers["Authorization"] = f"Bearer {creds.token}"

    def get(self, path):
        if self.session is None:
            print(f"  offline: skipping GET {path}")
            return None
        response = self.session.get(f"{API}/{path}", timeout=30)
        if response.status_code == 404:
            return None
        if response.status_code != 200:
            print(f"::notice::GET {path} returned {response.status_code}: {response.text[:300]}")
            return None
        return response.json()

    def write(self, method, path, body):
        print(f"  {method} {path}")
        print(json.dumps(body, indent=2, ensure_ascii=False))
        if self.dry_run or self.session is None:
            return None
        response = self.session.request(method, f"{API}/{path}", json=body, timeout=60)
        if response.status_code != 200:
            sys.exit(f"::error::{method} {path} failed with {response.status_code}: {response.text[:300]}")
        return response.json()

    def convert(self, package, money):
        if self.session is None:
            return None
        response = self.session.post(
            f"{API}/{package}/pricing:convertRegionPrices", json={"price": money}, timeout=60
        )
        if response.status_code != 200:
            sys.exit(f"::error::convertRegionPrices failed with {response.status_code}: {response.text[:300]}")
        return response.json()


def micros(price):
    return str(int(Decimal(price) * 1_000_000))


def money(price, currency):
    amount = Decimal(price)
    units = int(amount)
    return {"currencyCode": currency, "units": str(units), "nanos": int((amount - units) * 1_000_000_000)}


def tip_body(spec, product):
    return {
        "packageName": spec["packageName"],
        "sku": product["sku"],
        "status": "active",
        "purchaseType": "managedUser",
        "defaultPrice": {"priceMicros": micros(product["price"]), "currency": spec["currency"]},
        "listings": {
            lang: {"title": title, "description": spec["tips"]["description"][lang]}
            for lang, title in product["title"].items()
        },
        "defaultLanguage": spec["defaultLanguage"],
    }


def base_plan(api, spec, plan):
    base = money(plan["price"], spec["currency"])
    converted = api.convert(spec["packageName"], base)
    regional = []
    other = None
    if converted:
        regional = [
            {"regionCode": entry["regionCode"], "newSubscriberAvailability": True, "price": entry["price"]}
            for entry in converted["convertedRegionPrices"].values()
        ]
        other = dict(converted["convertedOtherRegionsPrice"], newSubscriberAvailability=True)
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


def sync_tips(api, spec):
    created = updated = 0
    for product in spec["tips"]["products"]:
        package, sku = spec["packageName"], product["sku"]
        body = tip_body(spec, product)
        if api.get(f"{package}/inappproducts/{sku}") is None:
            api.write("POST", f"{package}/inappproducts?autoConvertMissingPrices=true", body)
            created += 1
        else:
            api.write("PUT", f"{package}/inappproducts/{sku}?autoConvertMissingPrices=true", body)
            updated += 1
    return created, updated


def sync_subscription(api, spec, regions_version):
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
        result = api.write("POST", f"{package}/subscriptions?productId={product_id}&regionsVersion.version={regions_version}", body)
    else:
        result = api.write(
            "PATCH",
            f"{package}/subscriptions/{product_id}?updateMask=listings,basePlans"
            f"&regionsVersion.version={regions_version}&allowMissing=true",
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
    parser.add_argument("--regions-version", default=REGIONS_VERSION, help="Play regions version for subscription writes")
    args = parser.parse_args()

    key_file = os.environ.get("PLAY_KEY_FILE")
    if not key_file and not args.dry_run:
        sys.exit("::error::PLAY_KEY_FILE is not set; pass --dry-run to build payloads offline")
    spec = json.loads(SPEC.read_text(encoding="utf-8"))
    api = PlayApi(key_file, args.dry_run)

    print("== One-time tips")
    created, updated = sync_tips(api, spec)
    print("== Supporter subscription")
    sub_created, activated = sync_subscription(api, spec, args.regions_version)
    verb = "would be" if args.dry_run or api.session is None else "were"
    print(
        f"Tips: {created} {verb} created, {updated} {verb} updated. "
        f"Subscription: {'created' if sub_created else 'patched'}, {activated} base plan(s) {verb} activated."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
