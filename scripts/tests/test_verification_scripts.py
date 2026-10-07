"""The verification scripts must fail when what they check is absent, malformed or failed.

Pure stdlib; every fixture is synthetic.  Nothing here runs the game or a GPU.
"""
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
ROW = "blocks=aa heightmaps=bb post=cc biomes=dd structures=ee/0s1r"


def run(script, *args):
    done = subprocess.run([sys.executable, str(SCRIPTS / script), *map(str, args)], capture_output=True, text=True)
    return done.returncode, done.stdout + done.stderr


class Folder(unittest.TestCase):
    def setUp(self):
        self._temporary = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary.name)

    def tearDown(self):
        self._temporary.cleanup()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path


class CompareDigests(Folder):
    def compare(self, expected, actual, *options):
        return run("compare-digests.py", *options, self.write("expected.txt", expected), self.write("actual.txt", actual))

    def test_equal_digests_pass(self):
        text = f"0 0 {ROW}\n1 0 {ROW}\n"
        code, out = self.compare(text, text)
        self.assertEqual(0, code, out)
        self.assertIn("compared=2", out)

    def test_rows_of_coordinates_alone_are_malformed_even_when_identical(self):
        code, out = self.compare("0 0\n", "0 0\n")
        self.assertEqual(1, code, out)
        self.assertIn("malformed", out)

    def test_identically_missing_required_fields_fail(self):
        short = "0 0 blocks=aa heightmaps=bb\n"
        code, out = self.compare(short, short)
        self.assertEqual(1, code, out)
        self.assertIn("missing field", out)

    def test_duplicate_field_unknown_field_and_stray_token_fail(self):
        for row in (f"0 0 {ROW} blocks=zz", f"0 0 {ROW} light=ff", f"0 0 {ROW} oops", "0 0 blocks= heightmaps=bb post=cc biomes=dd structures=ee"):
            code, out = self.compare(row + "\n", row + "\n")
            self.assertEqual(1, code, row + " -> " + out)

    def test_chunk_listed_twice_fails(self):
        text = f"0 0 {ROW}\n0 0 {ROW}\n"
        self.assertEqual(1, self.compare(text, text)[0])

    def test_a_missing_chunk_fails_beside_valid_ones_whatever_is_ignored(self):
        expected = f"0 0 {ROW}\n1 0 {ROW}\n"
        actual = f"0 0 {ROW}\n1 0 MISSING\n"
        for options in ((), ("--ignore", "structures"), ("--only", "structures")):
            code, out = self.compare(expected, actual, *options)
            self.assertEqual(1, code, out)
            self.assertIn("missing_chunk", out)

    def test_structure_only_difference_is_a_failure_unless_ignored_and_is_what_only_sees(self):
        expected = f"0 0 {ROW}\n"
        actual = "0 0 blocks=aa heightmaps=bb post=cc biomes=dd structures=XX/0s1r\n"
        self.assertEqual(1, self.compare(expected, actual)[0])
        code, out = self.compare(expected, actual, "--ignore", "structures")
        self.assertEqual(0, code, out)
        self.assertIn("structures: 1 chunks differ", out)
        self.assertEqual(1, self.compare(expected, actual, "--only", "structures")[0])
        blocks = "0 0 blocks=ZZ heightmaps=bb post=cc biomes=dd structures=ee/0s1r\n"
        self.assertEqual(1, self.compare(expected, blocks, "--ignore", "structures")[0])
        self.assertEqual(0, self.compare(expected, blocks, "--only", "structures")[0])

    def test_absent_file_and_absent_chunk_fail(self):
        expected = self.write("expected.txt", f"0 0 {ROW}\n1 0 {ROW}\n")
        self.assertEqual(1, run("compare-digests.py", expected, self.root / "nowhere.txt")[0])
        self.assertEqual(1, self.compare(f"0 0 {ROW}\n1 0 {ROW}\n", f"0 0 {ROW}\n")[0])
        self.assertEqual(1, self.compare("", "")[0])


def region(chunks):
    """A region file holding the given {index: (kind, payload)} one sector each."""
    header = bytearray(8192)
    body = bytearray()
    for sector, (index, (kind, payload)) in enumerate(sorted(chunks.items()), 2):
        struct.pack_into(">I", header, index * 4, (sector << 8) | 1)
        chunk = struct.pack(">IB", len(payload) + 1, kind) + payload
        body += chunk + bytes(4096 - len(chunk))
    return bytes(header) + bytes(body)


NBT = zlib.compress(b"\x0a\x00\x00\x00")


class CheckRegionFiles(Folder):
    def folder(self, files):
        folder = self.root / "region"
        folder.mkdir()
        for name, data in files.items():
            (folder / name).write_bytes(data)
        return folder

    def test_valid_chunks_pass_and_are_counted(self):
        code, out = run("check-region-files.py", self.folder({"r.0.0.mca": region({0: (2, NBT), 5: (2, NBT)})}))
        self.assertEqual(0, code, out)
        self.assertIn("2 readable chunks, 0 problems", out)

    def test_a_missing_folder_is_a_problem_unless_optional(self):
        self.assertEqual(1, run("check-region-files.py", self.root / "nowhere")[0])
        code, out = run("check-region-files.py", self.folder({"r.0.0.mca": region({0: (2, NBT)})}), "--optional", self.root / "nowhere")
        self.assertEqual(0, code, out)

    def test_no_coverage_fails_when_coverage_is_expected(self):
        empty = self.folder({"r.0.0.mca": b""})
        code, out = run("check-region-files.py", empty)
        self.assertEqual(0, code, out)
        self.assertIn("(1 empty)", out)
        code, out = run("check-region-files.py", "--min-chunks", 1, empty)
        self.assertEqual(1, code, out)
        self.assertEqual(1, run("check-region-files.py", "--min-files", 1, empty)[0])

    def test_no_region_files_at_all_fails_when_coverage_is_expected(self):
        folder = self.root / "region"
        folder.mkdir()
        self.assertEqual(1, run("check-region-files.py", "--min-chunks", 1, folder)[0])

    def test_a_partial_header_and_a_corrupt_payload_are_problems(self):
        self.assertEqual(1, run("check-region-files.py", self.folder({"r.0.0.mca": b"\0" * 100}))[0])
        (self.root / "region" / "r.0.0.mca").write_bytes(region({0: (2, b"not zlib")}))
        self.assertEqual(1, run("check-region-files.py", self.root / "region")[0])

    def test_external_chunks_are_read_not_just_found(self):
        folder = self.folder({"r.1.-1.mca": region({33: (0x82, b"")})})
        external = folder / "c.33.-31.mcc"  # index 33 is local 1,1 of region 1,-1
        self.assertEqual(1, run("check-region-files.py", folder)[0], "missing external file")
        external.write_bytes(b"")
        self.assertEqual(1, run("check-region-files.py", folder)[0], "empty external file")
        external.write_bytes(b"garbage")
        self.assertEqual(1, run("check-region-files.py", folder)[0], "corrupt external file")
        external.write_bytes(NBT)
        code, out = run("check-region-files.py", folder)
        self.assertEqual(0, code, out)
        self.assertIn("1 readable chunks", out)


SUITE = '<testsuite name="x" tests="{tests}" failures="{failures}" errors="0" skipped="0"></testsuite>'


class SummarizeTests(Folder):
    def results(self, module, tests=3, failures=0):
        self.write(f"{module}/build/test-results/test/TEST-x.xml", SUITE.format(tests=tests, failures=failures))

    def tree(self):
        (self.root / "semantic-core" / "src" / "test").mkdir(parents=True)
        self.results("semantic-core")
        self.results("mod/targets/neoforge-1211")

    def summarize(self, *options):
        code, out = run("summarize-tests.py", "--root", self.root, *options)
        return code, json.loads(out)

    def test_results_under_mod_targets_are_found_under_the_target_name(self):
        self.tree()
        code, report = self.summarize()
        self.assertEqual(0, code)
        self.assertEqual({"semantic-core", "neoforge-1211"}, set(report["modules"]))
        self.assertEqual(6, report["counts"]["tests"])

    def test_a_failing_target_suite_fails_the_aggregate(self):
        self.tree()
        self.results("mod/targets/neoforge-1211", failures=1)
        code, report = self.summarize()
        self.assertEqual(1, code)
        self.assertEqual("FAILED", report["status"])

    def test_a_module_with_tests_and_no_results_fails_the_aggregate(self):
        self.tree()
        (self.root / "chunk-engine" / "src" / "test").mkdir(parents=True)
        code, report = self.summarize()
        self.assertEqual(1, code)
        self.assertEqual(["chunk-engine"], report["modulesWithoutResults"])

    def test_the_reference_target_has_to_be_there(self):
        (self.root / "semantic-core" / "src" / "test").mkdir(parents=True)
        self.results("semantic-core")
        code, report = self.summarize()
        self.assertEqual(1, code)
        self.assertEqual(["neoforge-1211"], report["modulesWithoutResults"])


class CheckBenchReport(Folder):
    def report(self, name="report.json", **changes):
        report = {"kind": "worldgennext_chunk_throughput", "status": "PASS", "endpoint": "MINECRAFT:SURFACE",
                  "radiusChunks": 45, "center": [4000, 4000], "seed": 0,
                  "measured": {"requested": 8281, "completed": 8281, "failed": 0}}
        report.update(changes)
        return self.write(name, json.dumps(report))

    def test_a_finished_run_of_what_was_asked_passes(self):
        code, out = run("check-bench-report.py", self.report(), "--endpoint", "SURFACE", "--radius", 45, "--seed", 0)
        self.assertEqual(0, code, out)

    def test_failed_incomplete_or_different_runs_fail(self):
        for changes in ({"status": "FAIL"}, {"measured": {"requested": 8281, "completed": 8000, "failed": 0}},
                        {"measured": {"requested": 8281, "completed": 8281, "failed": 2}}, {"endpoint": "MINECRAFT:NOISE"},
                        {"radiusChunks": 5}, {"kind": "something else"}):
            code, out = run("check-bench-report.py", self.report(**changes), "--endpoint", "SURFACE", "--radius", 45)
            self.assertEqual(1, code, f"{changes} -> {out}")
        self.assertEqual(1, run("check-bench-report.py", self.root / "nowhere.json")[0])
        self.assertEqual(1, run("check-bench-report.py", self.write("broken.json", "{"))[0])

    def test_two_runs_have_to_cover_the_same_chunks(self):
        one = self.report("one.json")
        self.assertEqual(0, run("check-bench-report.py", one, "--same-area-as", self.report("two.json"))[0])
        for changes in ({"center": [0, 0]}, {"seed": 12345}, {"radiusChunks": 44}):
            code, out = run("check-bench-report.py", one, "--same-area-as", self.report("other.json", **changes))
            self.assertEqual(1, code, f"{changes} -> {out}")


if __name__ == "__main__":
    unittest.main()
