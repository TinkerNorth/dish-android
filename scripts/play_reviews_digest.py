#!/usr/bin/env python3
"""Fetch recent Play reviews and core vitals into a markdown digest.

Reads the service-account key path from PLAY_KEY_FILE and writes to
GITHUB_STEP_SUMMARY when present (stdout otherwise). Every remote call
is best-effort: before the app is published the APIs return 403/404,
which this script reports as a notice instead of failing the job.
"""

import datetime
import json
import os
import sys

import google.auth.transport.requests
import requests
from google.oauth2 import service_account

PACKAGE = "com.tinkernorth.dish"
REVIEW_LIMIT = 20


def token_for(scope):
    creds = service_account.Credentials.from_service_account_file(
        os.environ["PLAY_KEY_FILE"], scopes=[scope]
    )
    creds.refresh(google.auth.transport.requests.Request())
    return creds.token


def fetch(url, scope, method="GET", body=None):
    response = requests.request(
        method,
        url,
        headers={"Authorization": f"Bearer {token_for(scope)}"},
        json=body,
        timeout=30,
    )
    if response.status_code != 200:
        return None, f"{response.status_code}: {response.text[:200]}"
    return response.json(), None


def reviews_section(lines):
    data, err = fetch(
        f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}/reviews"
        f"?maxResults={REVIEW_LIMIT}",
        "https://www.googleapis.com/auth/androidpublisher",
    )
    lines.append("## Recent reviews")
    if err:
        lines.append(f"_Not available ({err})_")
        return
    reviews = data.get("reviews", [])
    if not reviews:
        lines.append("_No reviews in the reachable window._")
        return
    for review in reviews:
        comment = review.get("comments", [{}])[0].get("userComment", {})
        stars = comment.get("starRating", "?")
        text = (comment.get("text", "") or "").strip().replace("\n", " ")
        locale = comment.get("reviewerLanguage", "?")
        replied = any("developerComment" in c for c in review.get("comments", []))
        flag = "" if replied else " **(unanswered)**"
        lines.append(f"- {stars}★ [{locale}] {text[:300]}{flag}")


def vitals_section(lines):
    end = datetime.date.today() - datetime.timedelta(days=2)
    start = end - datetime.timedelta(days=7)
    lines.append("## Vitals (7 days)")
    for metric_set, metric in (("crashRateMetricSet", "crashRate"), ("anrRateMetricSet", "anrRate")):
        body = {
            "timelineSpec": {
                "aggregationPeriod": "DAILY",
                "startTime": {"year": start.year, "month": start.month, "day": start.day},
                "endTime": {"year": end.year, "month": end.month, "day": end.day},
            },
            "metrics": [metric],
        }
        data, err = fetch(
            f"https://playdeveloperreporting.googleapis.com/v1beta1/apps/{PACKAGE}/{metric_set}:query",
            "https://www.googleapis.com/auth/playdeveloperreporting",
            method="POST",
            body=body,
        )
        if err:
            lines.append(f"- {metric}: _not available ({err})_")
            continue
        rows = data.get("rows", [])
        values = [
            float(m.get("decimalValue", {}).get("value", 0))
            for row in rows
            for m in row.get("metrics", [])
            if m.get("metric") == metric
        ]
        if values:
            lines.append(f"- {metric}: avg {sum(values) / len(values):.4%}, worst day {max(values):.4%}")
        else:
            lines.append(f"- {metric}: no data rows yet")


def main():
    lines = [f"# Play digest for {PACKAGE}", ""]
    reviews_section(lines)
    lines.append("")
    vitals_section(lines)
    output = "\n".join(lines) + "\n"
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(output)
    print(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
