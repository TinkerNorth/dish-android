#!/usr/bin/env python3
"""scripts/check_source_urls.py: the pinned sources in the CMake files, as the script reads them.

    python3 -m unittest discover -s scripts -p 'check_source_urls_test.py' -t scripts
"""
import hashlib
import unittest
from pathlib import Path

import check_source_urls as sources

DECLARE = """
FetchContent_Declare(
    opus
    URL      https://one.example/opus-1.tar.gz
             https://two.example/opus-1.tar.gz
    URL_HASH SHA256=%s
)
FetchContent_Declare(
    other
    URL      https://three.example/other.tar.gz
    URL_HASH SHA256=%s
)
"""
HASH_A = "a" * 64
HASH_B = "b" * 64


class ParseTest(unittest.TestCase):
    def test_every_declaration_gives_its_urls_and_its_hash(self):
        found = sources.pinned_sources(DECLARE % (HASH_A, HASH_B))

        self.assertEqual(
            [
                ("opus", ["https://one.example/opus-1.tar.gz", "https://two.example/opus-1.tar.gz"], HASH_A),
                ("other", ["https://three.example/other.tar.gz"], HASH_B),
            ],
            found,
        )

    def test_a_declaration_without_a_hash_is_refused(self):
        with self.assertRaises(ValueError):
            sources.pinned_sources("FetchContent_Declare(x URL https://a.example/x.tar.gz)")


class VerifyTest(unittest.TestCase):
    def test_a_matching_download_is_clean(self):
        data = b"tarball"
        digest = hashlib.sha256(data).hexdigest()

        self.assertIsNone(sources.problem_with("https://a.example/x", digest, lambda url: data))

    def test_a_different_download_names_both_digests(self):
        message = sources.problem_with("https://a.example/x", HASH_A, lambda url: b"other")

        self.assertIn(HASH_A, message)
        self.assertIn(hashlib.sha256(b"other").hexdigest(), message)

    def test_an_unreachable_url_is_a_problem(self):
        def fetch(url):
            raise OSError("404")

        self.assertIn("404", sources.problem_with("https://a.example/x", HASH_A, fetch))


class RepositoryTest(unittest.TestCase):
    def test_both_cmake_files_declare_the_same_sources_with_a_fallback_each(self):
        root = Path(__file__).resolve().parent.parent
        main = sources.pinned_sources((root / "app/src/main/cpp/CMakeLists.txt").read_text())
        host = sources.pinned_sources((root / "app/src/test/cpp/CMakeLists.txt").read_text())

        main_by_name = {name: (urls, digest) for name, urls, digest in main}
        host_by_name = {name: (urls, digest) for name, urls, digest in host}
        for name in ("libsodium", "opus"):
            with self.subTest(name=name):
                self.assertEqual(main_by_name[name], host_by_name[name])
        for name, urls, _ in main + host:
            with self.subTest(name=name):
                self.assertGreaterEqual(len(urls), 2, f"{name} has no second URL to fall back to")


if __name__ == "__main__":
    unittest.main()
