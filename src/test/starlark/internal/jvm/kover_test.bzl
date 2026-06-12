# Copyright 2024 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Tests for Kover code coverage integration."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load("//kotlin/internal/jvm:kover.bzl", "kover_coverage_launcher_script")

def _kover_coverage_launcher_script_test_impl(ctx):
    """Test that the wrapper writes the args file into TEST_UNDECLARED_OUTPUTS_DIR and enables the agent."""
    env = unittest.begin(ctx)

    result = kover_coverage_launcher_script(
        report_dir = "path/to/pkg",
        report_name = "my_test-kover_report.ic",
        agent = "external/kover/kover-jvm-agent.jar",
        workspace = "my_workspace",
        inner = "path/to/pkg/my_test_kover_launcher",
    )

    # The binary report lands under the runtime-only undeclared outputs dir so
    # Bazel collects it.
    asserts.true(
        env,
        "report.file=${TEST_UNDECLARED_OUTPUTS_DIR}/path/to/pkg/my_test-kover_report.ic" in result,
        "expected report.file under TEST_UNDECLARED_OUTPUTS_DIR, got:\n" + result,
    )
    asserts.true(
        env,
        'mkdir -p "${TEST_UNDECLARED_OUTPUTS_DIR}/path/to/pkg"' in result,
        "expected report directory to be created, got:\n" + result,
    )

    # The agent is enabled via JAVA_TOOL_OPTIONS (no stub-template changes needed).
    asserts.true(env, "-Xbootclasspath/a:external/kover/kover-jvm-agent.jar" in result)
    asserts.true(env, "-javaagent:external/kover/kover-jvm-agent.jar=file:${_kover_args}" in result)
    asserts.true(env, "export JAVA_TOOL_OPTIONS=" in result)

    # The wrapper execs the stock launcher from the runfiles tree.
    asserts.true(
        env,
        'exec "${_runfiles}/my_workspace/path/to/pkg/my_test_kover_launcher" "$@"' in result,
        "expected exec of the inner launcher, got:\n" + result,
    )

    return unittest.end(env)

_kover_coverage_launcher_script_test = unittest.make(_kover_coverage_launcher_script_test_impl)

def _kover_coverage_launcher_script_guarded_test_impl(ctx):
    """Test that the agent setup is guarded on TEST_UNDECLARED_OUTPUTS_DIR being set."""
    env = unittest.begin(ctx)

    result = kover_coverage_launcher_script(
        report_dir = "pkg",
        report_name = "test-kover_report.ic",
        agent = "maven/kover-agent-1.0.jar",
        workspace = "ws",
        inner = "pkg/test_kover_launcher",
    )

    # Setup only runs under coverage (the variable is set), so `bazel run` of the
    # target does not try to create a directory at the filesystem root.
    asserts.true(env, 'if [[ -n "${TEST_UNDECLARED_OUTPUTS_DIR:-}" ]]; then' in result)

    # All placeholders are substituted; none leak into the generated script.
    asserts.false(env, "@REPORT_DIR@" in result)
    asserts.false(env, "@REPORT_NAME@" in result)
    asserts.false(env, "@AGENT@" in result)
    asserts.false(env, "@WORKSPACE@" in result)
    asserts.false(env, "@INNER@" in result)

    return unittest.end(env)

_kover_coverage_launcher_script_guarded_test = unittest.make(_kover_coverage_launcher_script_guarded_test_impl)

def kover_test_suite(name):
    """Create the test suite for Kover integration tests.

    Args:
        name: The name of the test suite.
    """
    unittest.suite(
        name,
        _kover_coverage_launcher_script_test,
        _kover_coverage_launcher_script_guarded_test,
    )
