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
load("//kotlin/internal/jvm:kover.bzl", "kover_jvm_flags_setup")

def _kover_jvm_flags_setup_test_impl(ctx):
    """Test that kover_jvm_flags_setup emits the _setup_kover shell call with agent path, package and name."""
    env = unittest.begin(ctx)

    # Create a mock agent file object with a short_path attribute.
    mock_agent_file = struct(short_path = "external/kover/kover-jvm-agent.jar")

    result = kover_jvm_flags_setup([mock_agent_file], "path/to/pkg", "my_test")

    expected = '_setup_kover "external/kover/kover-jvm-agent.jar" "path/to/pkg" "my_test"'
    asserts.equals(env, expected, result)

    return unittest.end(env)

_kover_jvm_flags_setup_test = unittest.make(_kover_jvm_flags_setup_test_impl)

def _kover_jvm_flags_setup_format_test_impl(ctx):
    """Test the kover_jvm_flags_setup call format with different path patterns."""
    env = unittest.begin(ctx)

    mock_agent = struct(short_path = "maven/kover-agent-1.0.jar")

    result = kover_jvm_flags_setup([mock_agent], "pkg", "test")

    # Verify the call uses the first agent file and quotes all three arguments.
    asserts.true(env, result.startswith("_setup_kover "))
    asserts.true(env, '"maven/kover-agent-1.0.jar"' in result)
    asserts.true(env, '"pkg"' in result)
    asserts.true(env, result.endswith('"test"'))

    return unittest.end(env)

_kover_jvm_flags_setup_format_test = unittest.make(_kover_jvm_flags_setup_format_test_impl)

def kover_test_suite(name):
    """Create the test suite for Kover integration tests.

    Args:
        name: The name of the test suite.
    """
    unittest.suite(
        name,
        _kover_jvm_flags_setup_test,
        _kover_jvm_flags_setup_format_test,
    )
