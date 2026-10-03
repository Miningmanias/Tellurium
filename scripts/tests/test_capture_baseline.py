"""Pure stdlib baseline contract tests; fixtures are synthetic, not measurements."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "capture_baseline", Path(__file__).resolve().parents[1] / "capture-baseline.py")
baseline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(baseline)


def manifest():
    identity = {key: "a" * 64 for key in baseline.HASH_KEYS}
    workload = {"workloadId": "fixture", "warmupCoordinates": [[-1, 0]],
                "measuredCoordinates": [[0, 0], [1, 0]]}
    rows = []
    for endpoint in baseline.ENDPOINTS:
        for backend, mode in baseline.MODES.items():
            for temperature in ("COLD", "WARM"):
                for repetition in range(1, 4):
                    number = len(rows) + 1
                    row = {
                        "workloadId": "fixture", "identity": copy.deepcopy(identity),
                        "endpoint": endpoint, "backend": backend, "mode": mode,
                        "temperature": temperature, "repetition": repetition,
                        "runId": f"run-{number}", "launchId": f"launch-{number}",
                        "freshWorldId": f"world-{number}",
                        "warmupCoordinates": [[-1, 0]] if temperature == "WARM" else [],
                        "measuredCoordinates": [[0, 0], [1, 0]],
                        "compared": 8192, "comparedCoreChunks": 2, "mismatches": 0,
                        "wallNanos": 1_000_000_000, "p50Nanos": 100_000_000,
                        "p95Nanos": 500_000_000, "timingScope": "ENDPOINT_WALL",
                        "stageNanos": {"generation": 600_000_000},
                        "transferredBytes": 0, "memoryHighWaterBytes": 1024,
                        "failureCounts": dict.fromkeys(baseline.FAILURES, 0),
                        "chunksPerSecond": 2.0,
                    }
                    if endpoint == "SAVED":
                        row["stageNanos"].update(save=100_000_000, reopenValidation=200_000_000)
                    if backend == "GPU_IEEE_BITS":
                        row.update(gpuReceiptCount=2, commitCount=2, transferredBytes=16384)
                        row["gpuEvidence"] = [
                            {"coordinate": [x, 0], "backend": backend,
                             "deviceProducedBlocks": 4096, "mismatches": 0,
                             **{key: f"{number * 100 + x * 3 + i:064x}"
                                for i, key in enumerate(("receiptSha256", "commitSha256",
                                                         "comparisonSha256"))}}
                            for x in (0, 1)]
                    rows.append(row)
    return {"schemaVersion": 2, "identity": identity, "workloads": [workload], "rows": rows}


class BaselineTests(unittest.TestCase):
    def setUp(self):
        self.data = manifest()

    def reject(self, data=None, partial=False, message=None):
        with self.assertRaisesRegex(ValueError, message or "."):
            baseline.validate(self.data if data is None else data, partial)

    def gpu(self, endpoint="NOISE"):
        return next(row for row in self.data["rows"]
                    if row["backend"] == "GPU_IEEE_BITS" and row["endpoint"] == endpoint)

    def test_complete_matrix_and_saved_gpu(self):
        result = baseline.validate(self.data)
        self.assertEqual(result, {"status": "VALIDATED_BASELINE_INPUT",
                                 "releaseQualification": False, "rows": 54, "incompleteCells": []})
        self.assertEqual(self.gpu("SAVED")["stageNanos"]["reopenValidation"], 200_000_000)

    def test_partial_keeps_row_contracts(self):
        self.data["rows"] = [self.gpu("SAVED")]
        result = baseline.validate(self.data, partial=True)
        self.assertEqual(result["status"], "PARTIAL_VALIDATED_INPUT")
        self.assertIs(result["releaseQualification"], False)
        self.assertTrue(result["incompleteCells"])
        self.data["rows"][0]["commitCount"] = 1
        self.reject(partial=True)

    def test_partial_status_even_with_complete_matrix(self):
        self.assertEqual(baseline.validate(self.data, True)["status"], "PARTIAL_VALIDATED_INPUT")

    def test_bool_and_wrong_numeric_types(self):
        for key in ("wallNanos", "compared", "mismatches", "comparedCoreChunks",
                    "p50Nanos", "p95Nanos", "transferredBytes", "memoryHighWaterBytes",
                    "repetition", "chunksPerSecond"):
            for value in (True, False, "1", None):
                with self.subTest(key=key, value=value):
                    data = manifest()
                    data["rows"][0][key] = value
                    self.reject(data)
        self.data["rows"][0]["stageNanos"]["generation"] = True
        self.reject()

    def test_negative_zero_and_nonfinite_metrics(self):
        for key in ("wallNanos", "compared", "comparedCoreChunks", "p50Nanos",
                    "p95Nanos", "transferredBytes", "memoryHighWaterBytes", "mismatches"):
            for value in (-1, float("nan"), float("inf"), 1.0):
                with self.subTest(key=key, value=value):
                    data = manifest()
                    data["rows"][0][key] = value
                    self.reject(data)
        for key in ("wallNanos", "compared", "memoryHighWaterBytes", "p50Nanos"):
            data = manifest()
            data["rows"][0][key] = 0
            self.reject(data)

    def test_missing_row_or_backend_or_endpoint(self):
        self.data["rows"].pop()
        self.reject(message="missing matrix")
        for field, value in (("backend", "GPU_IEEE_BITS"), ("endpoint", "SAVED")):
            data = manifest()
            data["rows"] = [r for r in data["rows"] if r[field] != value]
            self.reject(data, message="missing matrix")

    def test_duplicate_repetition_and_identities(self):
        for key in ("runId", "launchId", "freshWorldId", "repetition"):
            with self.subTest(key=key):
                data = manifest()
                data["rows"][1][key] = data["rows"][0][key]
                self.reject(data, partial=True, message="duplicate")
        self.data["workloads"].append(copy.deepcopy(self.data["workloads"][0]))
        self.reject(message="duplicate workload")

    def test_frozen_hash_identity(self):
        for value in ("a" * 63, "z" * 64, True, None):
            data = manifest()
            data["identity"]["sourceSha256"] = value
            self.reject(data)
        self.data["rows"][0]["identity"]["jarSha256"] = "b" * 64
        self.reject(message="frozen identity")

    def test_coordinates_and_warmup_are_matched(self):
        for key, value in (("measuredCoordinates", [[True, 0], [1, 0]]),
                           ("measuredCoordinates", [[0, 0], [0, 0]]),
                           ("measuredCoordinates", [[9, 0], [1, 0]]),
                           ("warmupCoordinates", [[-1, 0]])):
            data = manifest()
            data["rows"][0][key] = value
            self.reject(data)
        self.data["workloads"][0]["warmupCoordinates"] = [[0, 0]]
        self.reject(message="overlaps")

    def test_empty_warmup_and_counts(self):
        self.data["workloads"][0]["warmupCoordinates"] = []
        for row in self.data["rows"]:
            row["warmupCoordinates"] = []
        self.reject(message="explicit warmup")
        for key, value in (("comparedCoreChunks", 1), ("compared", 1), ("mismatches", 1)):
            data = manifest()
            data["rows"][0][key] = value
            self.reject(data)

    def test_fallback_and_failure_counts(self):
        for key in baseline.FAILURES:
            for value in (1, -1, False):
                data = manifest()
                data["rows"][0]["failureCounts"][key] = value
                self.reject(data, partial=True)

    def test_unknown_claims_and_backend_mode(self):
        for key, value in (("backend", "GPU_NATIVE_DRAFT"), ("mode", "AUTO_SUPPORTED"),
                           ("endpoint", "KERNEL"), ("temperature", "HOT"),
                           ("tps", 20), ("saveNanos", 100), ("releaseQualification", True)):
            data = manifest()
            data["rows"][0][key] = value
            self.reject(data)

    def test_throughput_uses_wall_not_kernel(self):
        row = self.gpu()
        row["stageNanos"]["kernel"] = 1_000_000
        baseline.validate(self.data)
        row["chunksPerSecond"] = 2000
        self.reject(message="wall throughput")
        row["chunksPerSecond"] = 2
        row["timingScope"] = "KERNEL"
        self.reject(message="endpoint wall")

    def test_nonfinite_rates(self):
        for value in (float("nan"), float("inf"), -1, 0, 10 ** 400):
            data = manifest()
            data["rows"][0]["chunksPerSecond"] = value
            self.reject(data)

    def test_latency_and_stage_bounds(self):
        self.data["rows"][0]["p50Nanos"] = 600_000_000
        self.reject(message="latency")
        data = manifest()
        data["rows"][0]["stageNanos"]["generation"] = 1_000_000_001
        self.reject(data, message="stage exceeds")

    def test_saved_times_are_separate(self):
        for stage in ("generation", "save", "reopenValidation"):
            data = manifest()
            row = next(r for r in data["rows"] if r["endpoint"] == "SAVED")
            del row["stageNanos"][stage]
            self.reject(data)
        row = self.gpu("SAVED")
        row["stageNanos"]["save"] = 900_000_000
        self.reject(message="exceed wall")

    def test_non_saved_forbids_save_reopen(self):
        for endpoint in ("NOISE", "FULL"):
            for stage in ("save", "reopenValidation"):
                data = manifest()
                row = next(r for r in data["rows"] if r["endpoint"] == endpoint)
                row["stageNanos"][stage] = 1
                self.reject(data)

    def test_gpu_evidence_complete_and_linked(self):
        for key, value in (("gpuReceiptCount", True), ("commitCount", 1),
                           ("gpuEvidence", []), ("transferredBytes", 0)):
            self.data = manifest()
            self.gpu()[key] = value
            self.reject()
        self.data = manifest()
        row = self.gpu("SAVED")
        row["gpuEvidence"][1]["coordinate"] = [-1, 0]
        self.reject(message="cover measured")

    def test_gpu_link_identity_and_provenance(self):
        for key, value in (("backend", "CPU_OWNED"), ("deviceProducedBlocks", 0),
                           ("mismatches", 1), ("mismatches", False),
                           ("receiptSha256", "missing")):
            self.data = manifest()
            self.gpu()["gpuEvidence"][0][key] = value
            self.reject()
        self.data = manifest()
        row = self.gpu()
        row["gpuEvidence"][1]["receiptSha256"] = row["gpuEvidence"][0]["receiptSha256"]
        self.reject(message="duplicate GPU evidence")

    def test_cpu_cannot_claim_gpu_receipts(self):
        self.data["rows"][0]["gpuReceiptCount"] = 2
        self.reject(message="CPU row")

    def test_empty_wrong_and_legacy_schema(self):
        for data in ([], {}, {"rows": []}, {"rows": [{"compared": 1, "mismatches": 0}]}):
            self.reject(data)
        for key in ("workloads", "rows"):
            data = manifest()
            data[key] = []
            self.reject(data, partial=True)
        self.data["schemaVersion"] = True
        self.reject()

    def run_cli(self, contents, *arguments):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.json"
            path.write_text(contents, encoding="utf-8")
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = baseline.main([str(path), *arguments])
        return code, out.getvalue(), err.getvalue()

    def test_cli_status_and_error_exit(self):
        code, out, err = self.run_cli(json.dumps(self.data))
        self.assertEqual((code, err), (0, ""))
        self.assertEqual(json.loads(out)["status"], "VALIDATED_BASELINE_INPUT")
        self.data["rows"] = self.data["rows"][:1]
        self.assertEqual(self.run_cli(json.dumps(self.data))[0], 1)
        code, out, err = self.run_cli(json.dumps(self.data), "--partial")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["status"], "PARTIAL_VALIDATED_INPUT")

    def test_cli_rejects_json_constants_overflow_and_duplicate_keys(self):
        for contents in ('{"rows": NaN}', '{"rows": Infinity}',
                         '{"rows": -Infinity}', '{"rows": [], "rows": []}', '[',
                         json.dumps(self.data).replace('"chunksPerSecond": 2.0',
                                                       '"chunksPerSecond": 1e999')):
            code, out, err = self.run_cli(contents)
            self.assertEqual(code, 1)
            self.assertEqual(out, "")
            self.assertIn("rejected", err)


if __name__ == "__main__":
    unittest.main()
